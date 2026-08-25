package br.com.orquestrapay.platform.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class TesteIntegracaoRepositorioEventos {

    @Container
    private static final PostgreSQLContainer BANCO = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("eventos_teste")
            .withUsername("teste")
            .withPassword("teste");

    private static JdbcClient banco;
    private static RepositorioEventos repositorio;

    @BeforeAll
    static void prepararBanco() {
        var fonteDados = new DriverManagerDataSource(BANCO.getJdbcUrl(), BANCO.getUsername(), BANCO.getPassword());
        Flyway.configure()
                .dataSource(fonteDados)
                .locations("classpath:db/migration/shared")
                .load()
                .migrate();
        banco = JdbcClient.create(fonteDados);
        repositorio = new RepositorioEventos(banco);
    }

    @BeforeEach
    void limparBanco() {
        banco.sql("TRUNCATE auditoria_quarentena, evento_processado, evento_saida RESTART IDENTITY CASCADE")
                .update();
    }

    @Test
    void deveReivindicarUmLoteComUmaUnicaOperacaoEUmToken() {
        Instant agora = Instant.now().plusSeconds(5);
        adicionarEventos(5, agora.minusSeconds(10));

        List<EventoPendente> eventos = repositorio.reivindicarPendentes(
                5,
                10,
                agora.plusSeconds(1),
                agora.plusSeconds(31));

        assertThat(eventos).hasSize(5).isSortedAccordingTo((a, b) -> Long.compare(a.ordem(), b.ordem()));
        assertThat(eventos).extracting(EventoPendente::tokenBloqueio).doesNotContainNull();
        assertThat(eventos.stream().map(EventoPendente::tokenBloqueio).distinct()).hasSize(1);
        assertThat(banco.sql("SELECT COUNT(DISTINCT token_bloqueio) FROM evento_saida")
                .query(Integer.class)
                .single()).isEqualTo(1);
    }

    @Test
    void deveImpedirQueDoisPublicadoresReivindiquemOMesmoEvento() throws Exception {
        Instant agora = Instant.now().plusSeconds(5);
        adicionarEventos(10, agora.minusSeconds(10));
        CountDownLatch inicio = new CountDownLatch(1);

        try (var publicadores = Executors.newFixedThreadPool(2)) {
            var primeiro = publicadores.submit(() -> {
                inicio.await();
                return repositorio.reivindicarPendentes(5, 10, agora.plusSeconds(1), agora.plusSeconds(31));
            });
            var segundo = publicadores.submit(() -> {
                inicio.await();
                return repositorio.reivindicarPendentes(5, 10, agora.plusSeconds(1), agora.plusSeconds(31));
            });
            inicio.countDown();

            List<EventoPendente> loteA = primeiro.get();
            List<EventoPendente> loteB = segundo.get();
            Set<UUID> idsA = loteA.stream().map(EventoPendente::idEvento).collect(java.util.stream.Collectors.toSet());
            Set<UUID> idsB = loteB.stream().map(EventoPendente::idEvento).collect(java.util.stream.Collectors.toSet());

            assertThat(loteA).hasSize(5);
            assertThat(loteB).hasSize(5);
            assertThat(idsA).doesNotContainAnyElementsOf(idsB);
            assertThat(idsA).hasSize(5);
            assertThat(idsB).hasSize(5);
        }
    }

    @Test
    void devePreservarAOrdemDosEventosDaMesmaCompra() {
        Instant agora = Instant.now().plusSeconds(5);
        UUID idCompra = UUID.randomUUID();
        UUID primeiroId = adicionarEvento(idCompra, agora.minusSeconds(10));
        UUID segundoId = adicionarEvento(idCompra, agora.minusSeconds(10).plusMillis(1));

        List<EventoPendente> primeiroLote = repositorio.reivindicarPendentes(
                10,
                10,
                agora.plusSeconds(1),
                agora.plusSeconds(31));

        assertThat(primeiroLote).extracting(EventoPendente::idEvento).containsExactly(primeiroId);
        assertThat(repositorio.marcarPublicados(primeiroLote, agora.plusSeconds(2))).isEqualTo(1);

        List<EventoPendente> segundoLote = repositorio.reivindicarPendentes(
                10,
                10,
                agora.plusSeconds(3),
                agora.plusSeconds(33));
        assertThat(segundoLote).extracting(EventoPendente::idEvento).containsExactly(segundoId);
    }

    @Test
    void deveConfirmarSomenteOSubconjuntoReivindicadoPeloTokenCorreto() {
        Instant agora = Instant.now().plusSeconds(5);
        adicionarEventos(3, agora.minusSeconds(10));
        List<EventoPendente> eventos = repositorio.reivindicarPendentes(
                3,
                10,
                agora.plusSeconds(1),
                agora.plusSeconds(31));

        assertThat(repositorio.marcarPublicados(eventos.subList(0, 2), agora.plusSeconds(2))).isEqualTo(2);
        assertThat(banco.sql("SELECT COUNT(*) FROM evento_saida WHERE publicado_em IS NOT NULL")
                .query(Integer.class)
                .single()).isEqualTo(2);
        assertThat(banco.sql("SELECT token_bloqueio FROM evento_saida WHERE id_evento = :idEvento")
                .param("idEvento", eventos.get(2).idEvento())
                .query(UUID.class)
                .single()).isEqualTo(eventos.get(2).tokenBloqueio());
        assertThat(repositorio.marcarPublicado(
                eventos.get(2).idEvento(),
                UUID.randomUUID(),
                agora.plusSeconds(2))).isFalse();
    }

    @Test
    void deveReprocessarQuarentenaComMotivoEHistoricoSemInverterAOrdem() {
        Instant agora = Instant.now().plusSeconds(5);
        UUID idEmpresa = UUID.randomUUID();
        UUID idCompra = UUID.randomUUID();
        UUID primeiroId = adicionarEvento(idEmpresa, idCompra, agora.minusSeconds(10));
        UUID segundoId = adicionarEvento(idEmpresa, idCompra, agora.minusSeconds(9));
        EventoPendente primeiro = colocarPrimeiroEventoEmQuarentena(agora, primeiroId);

        assertThat(repositorio.listarQuarentena(idEmpresa, "ATIVA", 0, 20).itens())
                .singleElement()
                .satisfies(evento -> {
                    assertThat(evento.idEvento()).isEqualTo(primeiroId);
                    assertThat(evento.status()).isEqualTo("ATIVA");
                    assertThat(evento.tentativas()).isEqualTo(1);
                    assertThat(evento.ultimoErro()).isEqualTo("Kafka indisponivel no teste");
                });
        assertThat(repositorio.reivindicarPendentes(
                10, 3, agora.plusSeconds(2), agora.plusSeconds(32))).isEmpty();

        assertThat(repositorio.reprocessarQuarentena(
                UUID.randomUUID(),
                primeiroId,
                "operador-invalido",
                "Tentativa de outra empresa",
                agora.plusSeconds(3))).isFalse();
        assertThat(repositorio.reprocessarQuarentena(
                idEmpresa,
                primeiroId,
                "operador@orquestrapay.local",
                "Kafka normalizado; reprocessamento autorizado",
                agora.plusSeconds(3))).isTrue();

        List<EventoPendente> reprocessados = repositorio.reivindicarPendentes(
                10, 3, agora.plusSeconds(4), agora.plusSeconds(34));
        assertThat(reprocessados).extracting(EventoPendente::idEvento)
                .containsExactly(primeiroId);
        assertThat(reprocessados.getFirst().tentativas()).isZero();
        assertThat(repositorio.marcarPublicados(reprocessados, agora.plusSeconds(5))).isEqualTo(1);
        assertThat(repositorio.reivindicarPendentes(
                10, 3, agora.plusSeconds(6), agora.plusSeconds(36)))
                .extracting(EventoPendente::idEvento)
                .containsExactly(segundoId);
        assertThat(banco.sql("""
                        SELECT acao, responsavel, motivo,
                               tentativas_anteriores, erro_anterior
                          FROM auditoria_quarentena
                         WHERE id_evento = :idEvento
                        """)
                .param("idEvento", primeiro.idEvento())
                .query((resultado, linha) -> List.of(
                        resultado.getString("acao"),
                        resultado.getString("responsavel"),
                        resultado.getString("motivo"),
                        Integer.toString(resultado.getInt("tentativas_anteriores")),
                        resultado.getString("erro_anterior")))
                .single())
                .containsExactly(
                        "REPROCESSAR",
                        "operador@orquestrapay.local",
                        "Kafka normalizado; reprocessamento autorizado",
                        "1",
                        "Kafka indisponivel no teste");
        assertThat(repositorio.listarAuditoriaQuarentena(idEmpresa, primeiroId))
                .singleElement()
                .satisfies(auditoria -> {
                    assertThat(auditoria.acao()).isEqualTo("REPROCESSAR");
                    assertThat(auditoria.motivo())
                            .isEqualTo("Kafka normalizado; reprocessamento autorizado");
                    assertThat(auditoria.tentativasAnteriores()).isEqualTo(1);
                });
        assertThat(repositorio.listarAuditoriaQuarentena(UUID.randomUUID(), primeiroId))
                .isEmpty();
    }

    @Test
    void deveDescartarDefinitivamenteELiberarOProximoEventoDaCompra() {
        Instant agora = Instant.now().plusSeconds(5);
        UUID idEmpresa = UUID.randomUUID();
        UUID idCompra = UUID.randomUUID();
        UUID primeiroId = adicionarEvento(idEmpresa, idCompra, agora.minusSeconds(10));
        UUID segundoId = adicionarEvento(idEmpresa, idCompra, agora.minusSeconds(9));
        colocarPrimeiroEventoEmQuarentena(agora, primeiroId);

        assertThat(repositorio.descartarQuarentenaDefinitivamente(
                idEmpresa,
                primeiroId,
                "operador@orquestrapay.local",
                "Evento invalido confirmado pelo responsavel do dominio",
                agora.plusSeconds(3))).isTrue();
        assertThat(repositorio.descartarQuarentenaDefinitivamente(
                idEmpresa,
                primeiroId,
                "operador@orquestrapay.local",
                "Repeticao da mesma decisao",
                agora.plusSeconds(4))).isFalse();

        assertThat(repositorio.listarQuarentena(idEmpresa, "ATIVA", 0, 20).itens())
                .isEmpty();
        assertThat(repositorio.listarQuarentena(idEmpresa, "RESOLVIDA", 0, 20).itens())
                .singleElement()
                .satisfies(evento -> {
                    assertThat(evento.idEvento()).isEqualTo(primeiroId);
                    assertThat(evento.status()).isEqualTo("RESOLVIDA");
                    assertThat(evento.motivoResolucao())
                            .isEqualTo("Evento invalido confirmado pelo responsavel do dominio");
                    assertThat(evento.resolvidoEm())
                            .isCloseTo(agora.plusSeconds(3), within(1, ChronoUnit.MICROS));
                });
        assertThat(repositorio.resumir().quarentena()).isZero();
        assertThat(repositorio.reivindicarPendentes(
                10, 3, agora.plusSeconds(5), agora.plusSeconds(35)))
                .extracting(EventoPendente::idEvento)
                .containsExactly(segundoId);
        assertThat(banco.sql("""
                        SELECT acao
                          FROM auditoria_quarentena
                         WHERE id_evento = :idEvento
                        """)
                .param("idEvento", primeiroId)
                .query(String.class)
                .single()).isEqualTo("DESCARTAR_DEFINITIVAMENTE");
        assertThat(repositorio.listarAuditoriaQuarentena(idEmpresa, primeiroId))
                .extracting(AuditoriaQuarentena::acao)
                .containsExactly("DESCARTAR_DEFINITIVAMENTE");
    }

    private void adicionarEventos(int quantidade, Instant ocorridoEm) {
        for (int indice = 0; indice < quantidade; indice++) {
            adicionarEvento(UUID.randomUUID(), ocorridoEm.plusMillis(indice));
        }
    }

    private UUID adicionarEvento(UUID idCompra, Instant ocorridoEm) {
        return adicionarEvento(UUID.randomUUID(), idCompra, ocorridoEm);
    }

    private UUID adicionarEvento(UUID idEmpresa, UUID idCompra, Instant ocorridoEm) {
        UUID idEvento = UUID.randomUUID();
        repositorio.adicionar(
                idEvento,
                "COMPRA_CRIADA",
                1,
                UUID.randomUUID(),
                idEmpresa,
                idCompra,
                "teste",
                "{}",
                null,
                ocorridoEm);
        return idEvento;
    }

    private EventoPendente colocarPrimeiroEventoEmQuarentena(
            Instant agora,
            UUID idEventoEsperado) {
        List<EventoPendente> reivindicados = repositorio.reivindicarPendentes(
                10, 3, agora.plusSeconds(1), agora.plusSeconds(31));
        assertThat(reivindicados).extracting(EventoPendente::idEvento)
                .containsExactly(idEventoEsperado);
        EventoPendente evento = reivindicados.getFirst();
        assertThat(repositorio.registrarFalha(
                evento.idEvento(),
                evento.tokenBloqueio(),
                "Kafka indisponivel no teste",
                agora.plusSeconds(2),
                agora.plusSeconds(2))).isTrue();
        return evento;
    }
}
