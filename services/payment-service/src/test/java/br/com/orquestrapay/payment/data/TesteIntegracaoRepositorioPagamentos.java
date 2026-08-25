package br.com.orquestrapay.payment.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import br.com.orquestrapay.payment.domain.StatusPagamento;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
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
}
