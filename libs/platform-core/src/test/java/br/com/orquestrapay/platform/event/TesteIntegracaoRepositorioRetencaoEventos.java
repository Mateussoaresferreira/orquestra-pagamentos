package br.com.orquestrapay.platform.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import br.com.orquestrapay.platform.data.DatasSql;
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
class TesteIntegracaoRepositorioRetencaoEventos {

    @Container
    private static final PostgreSQLContainer BANCO = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("retencao_teste")
            .withUsername("teste")
            .withPassword("teste");

    private static JdbcClient banco;
    private static RepositorioRetencaoEventos repositorio;

    @BeforeAll
    static void prepararBanco() {
        var fonteDados = new DriverManagerDataSource(BANCO.getJdbcUrl(), BANCO.getUsername(), BANCO.getPassword());
        Flyway.configure()
                .dataSource(fonteDados)
                .locations("classpath:db/migration/shared")
                .load()
                .migrate();
        banco = JdbcClient.create(fonteDados);
        repositorio = new RepositorioRetencaoEventos(banco);
    }

    @BeforeEach
    void limparBanco() {
        banco.sql("TRUNCATE auditoria_quarentena, evento_processado, evento_saida RESTART IDENTITY CASCADE")
                .update();
    }

    @Test
    void deveRemoverProcessadosVencidosEmLotesSemTocarNosRecentes() {
        Instant agora = Instant.parse("2026-08-25T00:00:00Z");
        adicionarProcessado(agora.minusSeconds(100));
        adicionarProcessado(agora.minusSeconds(99));
        adicionarProcessado(agora.minusSeconds(98));
        adicionarProcessado(agora.minusSeconds(10));

        assertThat(repositorio.removerProcessadosAnterioresA(agora.minusSeconds(50), 2)).isEqualTo(2);
        assertThat(total("evento_processado")).isEqualTo(2);
        assertThat(repositorio.removerProcessadosAnterioresA(agora.minusSeconds(50), 2)).isEqualTo(1);
        assertThat(total("evento_processado")).isEqualTo(1);
    }

    @Test
    void deveRemoverApenasOutboxPublicadaEExpirada() {
        Instant agora = Instant.parse("2026-08-25T00:00:00Z");
        adicionarEvento(agora.minusSeconds(100), agora.minusSeconds(90), null);
        adicionarEvento(agora.minusSeconds(20), agora.minusSeconds(10), null);
        adicionarEvento(agora.minusSeconds(100), null, null);

        assertThat(repositorio.removerPublicadosAnterioresA(agora.minusSeconds(50), 100)).isEqualTo(1);
        assertThat(total("evento_saida")).isEqualTo(2);
        assertThat(banco.sql("SELECT COUNT(*) FROM evento_saida WHERE publicado_em IS NULL")
                .query(Integer.class)
                .single()).isEqualTo(1);
    }

    @Test
    void devePreservarEventoDescartadoEnquantoSuaAuditoriaEstiverRetida() {
        Instant agora = Instant.parse("2026-08-25T00:00:00Z");
        UUID idEvento = adicionarEvento(agora.minusSeconds(200), null, agora.minusSeconds(190));
        adicionarAuditoria(idEvento, agora.minusSeconds(180));

        assertThat(repositorio.removerDescartadosAnterioresA(agora.minusSeconds(50), 100)).isZero();
        assertThat(repositorio.removerAuditoriasQuarentenaAnterioresA(agora.minusSeconds(50), 100)).isEqualTo(1);
        assertThat(repositorio.removerDescartadosAnterioresA(agora.minusSeconds(50), 100)).isEqualTo(1);
        assertThat(total("evento_saida")).isZero();
    }

    private void adicionarProcessado(Instant processadoEm) {
        banco.sql("""
                        INSERT INTO evento_processado (id_evento, consumidor, processado_em)
                        VALUES (:idEvento, 'consumidor-teste', :processadoEm)
                        """)
                .param("idEvento", UUID.randomUUID())
                .param("processadoEm", DatasSql.gravar(processadoEm))
                .update();
    }

    private UUID adicionarEvento(Instant ocorridoEm, Instant publicadoEm, Instant descartadoEm) {
        UUID idEvento = UUID.randomUUID();
        banco.sql("""
                        INSERT INTO evento_saida (
                            id_evento, tipo, versao, id_correlacao, id_empresa,
                            id_compra, origem, conteudo, ocorrido_em, publicado_em, descartado_em
                        ) VALUES (
                            :idEvento, 'EVENTO_TESTE', 1, :idCorrelacao, :idEmpresa,
                            :idCompra, 'teste', '{}', :ocorridoEm, :publicadoEm, :descartadoEm
                        )
                        """)
                .param("idEvento", idEvento)
                .param("idCorrelacao", UUID.randomUUID())
                .param("idEmpresa", UUID.randomUUID())
                .param("idCompra", UUID.randomUUID())
                .param("ocorridoEm", DatasSql.gravar(ocorridoEm))
                .param("publicadoEm", publicadoEm == null ? null : DatasSql.gravar(publicadoEm))
                .param("descartadoEm", descartadoEm == null ? null : DatasSql.gravar(descartadoEm))
                .update();
        return idEvento;
    }

    private void adicionarAuditoria(UUID idEvento, Instant registradaEm) {
        banco.sql("""
                        INSERT INTO auditoria_quarentena (
                            id_auditoria, id_evento, acao, responsavel, detalhes, registrada_em
                        ) VALUES (
                            :idAuditoria, :idEvento, 'REPROCESSAR', 'operador-teste', 'teste', :registradaEm
                        )
                        """)
                .param("idAuditoria", UUID.randomUUID())
                .param("idEvento", idEvento)
                .param("registradaEm", DatasSql.gravar(registradaEm))
                .update();
    }

    private int total(String tabela) {
        String consulta = switch (tabela) {
            case "evento_processado" -> "SELECT COUNT(*) FROM evento_processado";
            case "evento_saida" -> "SELECT COUNT(*) FROM evento_saida";
            default -> throw new IllegalArgumentException("Tabela de teste desconhecida");
        };
        return banco.sql(consulta)
                .query(Integer.class)
                .single();
    }
}
