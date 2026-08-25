package br.com.orquestrapay.payment.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import br.com.orquestrapay.payment.domain.StatusPagamento;
import br.com.orquestrapay.contracts.MetodoPagamento;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class TesteIntegracaoRepositorioPagamentos {

    @Container
    private static final PostgreSQLContainer BANCO = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("pagamento_teste")
            .withUsername("teste")
            .withPassword("teste");

    private static JdbcClient banco;
    private static RepositorioPagamentos repositorio;
    private static TransactionTemplate transacao;

    @BeforeAll
    static void prepararBanco() {
        var fonteDados = new DriverManagerDataSource(BANCO.getJdbcUrl(), BANCO.getUsername(), BANCO.getPassword());
        Flyway.configure()
                .dataSource(fonteDados)
                .locations("classpath:db/migration/shared", "classpath:db/migration/service")
                .load()
                .migrate();
        banco = JdbcClient.create(fonteDados);
        repositorio = new RepositorioPagamentos(banco);
        transacao = new TransactionTemplate(new DataSourceTransactionManager(fonteDados));
    }

    @Test
    void devePersistirAutorizarEstornarEIsolarPagamentoPorEmpresa() {
        UUID idEmpresa = UUID.randomUUID();
        UUID outraEmpresa = UUID.randomUUID();
        UUID idCompra = UUID.randomUUID();
        UUID idPagamento = UUID.randomUUID();
        Instant agora = Instant.parse("2026-08-23T12:00:00Z");

        repositorio.adicionar(
                idPagamento,
                idEmpresa,
                idCompra,
                new BigDecimal("199.90"),
                "BRL",
                "a".repeat(64),
                StatusPagamento.AUTORIZADO,
                "autorizacao-42",
                "Aprovado",
                agora);

        assertThat(repositorio.existePorCompra(idEmpresa, idCompra)).isTrue();
        assertThat(repositorio.existePorCompra(outraEmpresa, idCompra)).isFalse();
        assertThat(repositorio.bloquear(idPagamento)).get()
                .extracting(RepositorioPagamentos.Pagamento::status)
                .isEqualTo(StatusPagamento.AUTORIZADO);
        assertThat(repositorio.buscar(idEmpresa, idCompra)).get()
                .extracting(resposta -> resposta.status(), resposta -> resposta.idAutorizacao())
                .containsExactly("AUTORIZADO", "autorizacao-42");
        assertThat(repositorio.buscar(outraEmpresa, idCompra)).isEmpty();
        assertThat(repositorio.buscarPorPagamentos(idEmpresa, List.of(idPagamento)))
                .containsOnlyKeys(idPagamento);

        repositorio.marcarEstornado(idPagamento, "estorno-42", agora.plusSeconds(1));
        repositorio.registrarDivergencia(
                idEmpresa,
                idPagamento,
                "VALOR_DIVERGENTE",
                "Valor do provedor divergiu",
                agora.plusSeconds(2));
        repositorio.registrarDivergencia(
                idEmpresa,
                idPagamento,
                "VALOR_DIVERGENTE",
                "Valor do provedor continua divergente",
                agora.plusSeconds(3));

        assertThat(repositorio.buscar(idEmpresa, idCompra).orElseThrow().status()).isEqualTo("ESTORNADO");
        assertThat(banco.sql("SELECT COUNT(*) FROM tentativa_pagamento WHERE id_pagamento = :id")
                .param("id", idPagamento)
                .query(Integer.class)
                .single()).isEqualTo(2);
        assertThat(banco.sql("SELECT COUNT(*) FROM divergencia_conciliacao WHERE id_pagamento = :id")
                .param("id", idPagamento)
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(banco.sql("SELECT detalhes FROM divergencia_conciliacao WHERE id_pagamento = :id")
                .param("id", idPagamento)
                .query(String.class)
                .single()).isEqualTo("Valor do provedor continua divergente");
    }

    @Test
    void deveRegistrarConciliacaoComDivergenciasSemTruncarOStatus() {
        UUID idEmpresa = UUID.randomUUID();
        Instant iniciadaEm = Instant.parse("2026-08-23T12:00:00Z");

        UUID idConciliacao = repositorio.registrarConciliacao(
                idEmpresa,
                1,
                1,
                iniciadaEm,
                iniciadaEm.plusSeconds(2));

        assertThat(repositorio.listarConciliacoes(idEmpresa, 10))
                .singleElement()
                .satisfies(conciliacao -> {
                    assertThat(conciliacao.idConciliacao()).isEqualTo(idConciliacao);
                    assertThat(conciliacao.status()).isEqualTo("CONCLUIDA_COM_DIVERGENCIAS");
                    assertThat(conciliacao.divergenciasEncontradas()).isEqualTo(1);
                });
    }

    @Test
    void devePersistirConciliacaoBidirecionalEReaproveitarOMesmoExtrato() {
        UUID idEmpresa = UUID.randomUUID();
        UUID idCompra = UUID.randomUUID();
        Instant agora = Instant.parse("2026-08-25T15:00:00Z");
        UUID idPagamento = repositorio.adicionarPendente(
                idEmpresa,
                idCompra,
                new BigDecimal("59.90"),
                "BRL",
                "v2:ativa:token-cifrado",
                MetodoPagamento.CARTAO,
                1,
                agora);
        repositorio.concluirAutorizacao(
                idPagamento,
                StatusPagamento.AUTORIZADO,
                "principal",
                "c".repeat(64),
                "aut-conciliacao",
                "Aprovado",
                agora.plusSeconds(1));

        assertThat(repositorio.buscarConciliaveisPorIds(idEmpresa, List.of(idPagamento)))
                .containsOnlyKeys(idPagamento);
        assertThat(repositorio.buscarConciliaveisNaJanela(
                idEmpresa,
                "principal",
                "BRL",
                agora.minusSeconds(1),
                agora.plusSeconds(60),
                501))
                .extracting(RepositorioPagamentos.PagamentoConciliavel::idPagamento)
                .containsExactly(idPagamento);

        var primeira = repositorio.iniciarConciliacao(
                idEmpresa,
                "principal",
                "extrato-integracao-001",
                "d".repeat(64),
                "BRL",
                agora.minusSeconds(1),
                agora.plusSeconds(60),
                1,
                agora.plusSeconds(2));
        assertThat(primeira.nova()).isTrue();
        repositorio.registrarOcorrenciaConciliacao(
                primeira.conciliacao().idConciliacao(),
                idPagamento,
                "STATUS_DIVERGENTE",
                "Status divergente no teste",
                agora.plusSeconds(3));
        repositorio.concluirConciliacao(
                primeira.conciliacao().idConciliacao(),
                1,
                0,
                1,
                1,
                agora.plusSeconds(4));

        var repetida = repositorio.iniciarConciliacao(
                idEmpresa,
                "principal",
                "extrato-integracao-001",
                "d".repeat(64),
                "BRL",
                agora.minusSeconds(1),
                agora.plusSeconds(60),
                1,
                agora.plusSeconds(5));

        assertThat(repetida.nova()).isFalse();
        assertThat(repetida.conciliacao())
                .satisfies(conciliacao -> {
                    assertThat(conciliacao.status()).isEqualTo("CONCLUIDA_COM_DIVERGENCIAS");
                    assertThat(conciliacao.divergenciasEncontradas()).isEqualTo(1);
                    assertThat(conciliacao.hashExtrato()).isEqualTo("d".repeat(64));
                });
        assertThat(repositorio.listarOcorrenciasConciliacao(
                primeira.conciliacao().idConciliacao()))
                .singleElement()
                .satisfies(ocorrencia -> {
                    assertThat(ocorrencia.tipo()).isEqualTo("STATUS_DIVERGENTE");
                    assertThat(ocorrencia.idPagamento()).isEqualTo(idPagamento);
                });
        assertThat(repositorio.listarConciliacoes(idEmpresa, 10))
                .anySatisfy(conciliacao -> {
                    assertThat(conciliacao.identificadorExtrato())
                            .isEqualTo("extrato-integracao-001");
                    assertThat(conciliacao.registrosLocais()).isEqualTo(1);
                });
    }

    @Test
    void devePreservarOProvedorQuandoOResultadoDaAutorizacaoForAmbiguo() {
        UUID idEmpresa = UUID.randomUUID();
        UUID idCompra = UUID.randomUUID();
        Instant agora = Instant.parse("2026-08-25T12:00:00Z");
        UUID idPagamento = repositorio.adicionarPendente(
                idEmpresa,
                idCompra,
                new BigDecimal("79.90"),
                "BRL",
                "v2:ativa:token-cifrado",
                MetodoPagamento.CARTAO,
                1,
                agora);

        repositorio.marcarProcessando(idPagamento, StatusPagamento.PROCESSANDO, agora.plusSeconds(1));
        repositorio.marcarConfirmacaoPendente(
                idPagamento,
                "principal",
                "Resposta perdida depois do envio",
                agora.plusSeconds(2));

        var resposta = repositorio.buscar(idEmpresa, idCompra).orElseThrow();
        assertThat(resposta.status()).isEqualTo("CONFIRMACAO_PENDENTE");
        assertThat(resposta.provedor()).isEqualTo("principal");

        repositorio.marcarProcessando(
                idPagamento,
                StatusPagamento.PROCESSANDO,
                agora.plusSeconds(3));
        repositorio.concluirAutorizacao(
                idPagamento,
                StatusPagamento.AUTORIZADO,
                "principal",
                "b".repeat(64),
                "aut-recuperada",
                "Resposta idempotente recuperada",
                agora.plusSeconds(4));

        assertThat(repositorio.buscar(idEmpresa, idCompra).orElseThrow())
                .satisfies(pagamento -> {
                    assertThat(pagamento.status()).isEqualTo("AUTORIZADO");
                    assertThat(pagamento.provedor()).isEqualTo("principal");
                    assertThat(pagamento.idAutorizacao()).isEqualTo("aut-recuperada");
                });
    }

    @Test
    void deveAgendarUmaUnicaDevolucaoQuandoPixExpiradoForConfirmadoTardiamente() {
        UUID idEmpresa = UUID.randomUUID();
        UUID idCompra = UUID.randomUUID();
        Instant agora = Instant.parse("2026-08-25T13:00:00Z");
        UUID idPagamento = repositorio.adicionarPendente(
                idEmpresa,
                idCompra,
                new BigDecimal("49.90"),
                "BRL",
                "v2:ativa:token-cifrado",
                MetodoPagamento.PIX,
                1,
                agora);
        repositorio.concluirCriacaoPix(
                idPagamento,
                "principal",
                "pix-tardio",
                "copia-e-cola",
                "imagem-base64",
                agora.plusSeconds(60),
                agora.plusSeconds(1));

        assertThat(repositorio.expirarPix(
                idPagamento,
                "Prazo expirado",
                agora.plusSeconds(61))).isTrue();
        assertThat(repositorio.agendarEstornoPixConfirmadoAposExpiracao(
                idPagamento,
                "pix-tardio",
                agora.plusSeconds(62))).isTrue();
        assertThat(repositorio.agendarEstornoPixConfirmadoAposExpiracao(
                idPagamento,
                "pix-tardio",
                agora.plusSeconds(63))).isFalse();

        assertThat(repositorio.buscar(idEmpresa, idCompra).orElseThrow())
                .satisfies(pagamento -> {
                    assertThat(pagamento.status()).isEqualTo("ESTORNO_PENDENTE");
                    assertThat(pagamento.idAutorizacao()).isEqualTo("pix-tardio");
                    assertThat(pagamento.motivo()).contains("devolucao automatica pendente");
                });
        assertThat(banco.sql("""
                        SELECT COUNT(*)
                          FROM tentativa_pagamento
                         WHERE id_pagamento = :idPagamento
                           AND resultado = 'CONFIRMADO_APOS_EXPIRACAO'
                        """)
                .param("idPagamento", idPagamento)
                .query(Integer.class)
                .single()).isEqualTo(1);
    }

    @Test
    void deveResolverCorridaEntreExpiracaoEConfirmacaoSemPerderORecebimento() throws Exception {
        UUID idEmpresa = UUID.randomUUID();
        UUID idCompra = UUID.randomUUID();
        Instant agora = Instant.parse("2026-08-25T14:00:00Z");
        UUID idPagamento = repositorio.adicionarPendente(
                idEmpresa,
                idCompra,
                new BigDecimal("89.90"),
                "BRL",
                "v2:ativa:token-cifrado",
                MetodoPagamento.PIX,
                1,
                agora);
        repositorio.concluirCriacaoPix(
                idPagamento,
                "principal",
                "pix-corrida",
                "copia-e-cola",
                "imagem-base64",
                agora.plusSeconds(60),
                agora.plusSeconds(1));

        var participantesProntos = new CountDownLatch(2);
        var iniciarDisputa = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var expiracao = executor.submit(() -> transacao.execute(status -> {
                sinalizarEEsperar(participantesProntos, iniciarDisputa);
                var pagamentosExpirados = repositorio.bloquearPixExpirados(
                        agora.plusSeconds(61), 1);
                return !pagamentosExpirados.isEmpty()
                        && repositorio.expirarPix(
                                idPagamento,
                                "Expiracao concorrente",
                                agora.plusSeconds(61));
            }));
            var confirmacao = executor.submit(() -> transacao.execute(status -> {
                sinalizarEEsperar(participantesProntos, iniciarDisputa);
                var pagamento = repositorio.bloquearPorPix("principal", "pix-corrida")
                        .orElseThrow();
                return switch (pagamento.status()) {
                    case AGUARDANDO_CONFIRMACAO -> repositorio.confirmarPix(
                            idPagamento, "pix-corrida", agora.plusSeconds(61));
                    case EXPIRADO -> repositorio.agendarEstornoPixConfirmadoAposExpiracao(
                            idPagamento, "pix-corrida", agora.plusSeconds(61));
                    default -> false;
                };
            }));

            assertThat(participantesProntos.await(5, TimeUnit.SECONDS)).isTrue();
            iniciarDisputa.countDown();
            expiracao.get(10, TimeUnit.SECONDS);
            assertThat(confirmacao.get(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(repositorio.buscar(idEmpresa, idCompra).orElseThrow().status())
                .isIn("AUTORIZADO", "ESTORNO_PENDENTE");
        assertThat(banco.sql("""
                        SELECT COUNT(*)
                          FROM tentativa_pagamento
                         WHERE id_pagamento = :idPagamento
                           AND resultado = 'CONFIRMADO_APOS_EXPIRACAO'
                        """)
                .param("idPagamento", idPagamento)
                .query(Integer.class)
                .single()).isLessThanOrEqualTo(1);
    }

    @Test
    void deveDistinguirWebhookDuplicadoDeWebhookConflitante() {
        var webhooks = new RepositorioWebhooksProvedor(banco);
        UUID idEvento = UUID.randomUUID();
        Instant agora = Instant.parse("2026-08-25T16:00:00Z");

        assertThat(webhooks.registrar("principal", idEvento, "a".repeat(64), agora))
                .isEqualTo(RepositorioWebhooksProvedor.ResultadoRegistro.NOVO);
        assertThat(webhooks.registrar("principal", idEvento, "a".repeat(64), agora.plusSeconds(1)))
                .isEqualTo(RepositorioWebhooksProvedor.ResultadoRegistro.DUPLICADO);
        assertThat(webhooks.registrar("principal", idEvento, "b".repeat(64), agora.plusSeconds(2)))
                .isEqualTo(RepositorioWebhooksProvedor.ResultadoRegistro.CONFLITANTE);
    }

    private static void sinalizarEEsperar(
            CountDownLatch participantesProntos,
            CountDownLatch iniciarDisputa) {
        participantesProntos.countDown();
        try {
            if (!iniciarDisputa.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("A disputa concorrente nao iniciou no tempo esperado");
            }
        } catch (InterruptedException excecao) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Teste concorrente interrompido", excecao);
        }
    }
}
