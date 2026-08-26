package br.com.orquestrapay.risk.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import br.com.orquestrapay.risk.domain.ClassificacaoComparacaoRisco;
import br.com.orquestrapay.risk.domain.ResultadoAvaliacaoRisco;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class TesteIntegracaoRepositorioRisco {

    @Container
    private static final PostgreSQLContainer BANCO = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("risco_teste")
            .withUsername("teste")
            .withPassword("teste");

    private static RepositorioRisco repositorio;

    @BeforeAll
    static void prepararBanco() {
        var fonteDados = new DriverManagerDataSource(BANCO.getJdbcUrl(), BANCO.getUsername(), BANCO.getPassword());
        Flyway.configure()
                .dataSource(fonteDados)
                .locations("classpath:db/migration/shared", "classpath:db/migration/service")
                .load()
                .migrate();
        repositorio = new RepositorioRisco(JdbcClient.create(fonteDados));
    }

    @Test
    void deveConsultarHistoricoPorJanelaSemCruzarEmpresas() {
        UUID idEmpresa = UUID.randomUUID();
        UUID outraEmpresa = UUID.randomUUID();
        UUID primeiraCompra = UUID.randomUUID();
        UUID segundaCompra = UUID.randomUUID();
        Instant agora = Instant.parse("2026-08-23T12:00:00Z");

        repositorio.adicionar(
                idEmpresa,
                primeiraCompra,
                "cliente-1",
                "dispositivo-compartilhado",
                new BigDecimal("149.90"),
                "BR",
                0,
                true,
                "Sem sinais",
                "regras-transacionais",
                "1.0.0",
                agora.minus(5, ChronoUnit.MINUTES));
        repositorio.adicionar(
                idEmpresa,
                segundaCompra,
                "cliente-2",
                "dispositivo-compartilhado",
                new BigDecimal("249.90"),
                "BR",
                25,
                true,
                "Sinal moderado",
                "regras-transacionais",
                "1.0.0",
                agora);

        assertThat(repositorio.existePorCompra(idEmpresa, primeiraCompra)).isTrue();
        assertThat(repositorio.existePorCompra(outraEmpresa, primeiraCompra)).isFalse();
        assertThat(repositorio.contarComprasRecentes(
                idEmpresa,
                "cliente-1",
                agora.minus(10, ChronoUnit.MINUTES))).isEqualTo(1);
        assertThat(repositorio.contarComprasRecentes(
                idEmpresa,
                "cliente-1",
                agora.minus(1, ChronoUnit.MINUTES))).isZero();
        assertThat(repositorio.contarClientesNoDispositivo(
                idEmpresa,
                "dispositivo-compartilhado",
                "cliente-atual",
                agora.minus(1, ChronoUnit.DAYS))).isEqualTo(2);

        assertThat(repositorio.buscar(idEmpresa, segundaCompra)).get()
                .extracting(
                        resposta -> resposta.pontuacao(),
                        resposta -> resposta.aprovada(),
                        resposta -> resposta.modeloDecisao(),
                        resposta -> resposta.versaoModeloDecisao())
                .containsExactly(25, true, "regras-transacionais", "1.0.0");
        assertThat(repositorio.buscar(outraEmpresa, segundaCompra)).isEmpty();

        var champion = new ResultadoAvaliacaoRisco(
                "regras-transacionais", "1.0.0", 25, true, List.of(), "Sinal moderado");
        var challenger = new ResultadoAvaliacaoRisco(
                "regras-transacionais", "1.1.0", 75, false, List.of(), "Sinal forte");
        assertThat(repositorio.adicionarComparacao(
                        idEmpresa,
                        segundaCompra,
                        champion,
                        challenger,
                        ClassificacaoComparacaoRisco.CHALLENGER_MAIS_RESTRITIVO,
                        agora.plusSeconds(1)))
                .isTrue();
        assertThat(repositorio.adicionarComparacao(
                        idEmpresa,
                        segundaCompra,
                        champion,
                        challenger,
                        ClassificacaoComparacaoRisco.CHALLENGER_MAIS_RESTRITIVO,
                        agora.plusSeconds(2)))
                .isFalse();

        assertThat(repositorio.buscarComparacao(idEmpresa, segundaCompra)).get()
                .extracting(
                        resposta -> resposta.classificacao(),
                        resposta -> resposta.diferencaPontuacao())
                .containsExactly(
                        ClassificacaoComparacaoRisco.CHALLENGER_MAIS_RESTRITIVO,
                        50);
        assertThat(repositorio.buscarComparacao(outraEmpresa, segundaCompra)).isEmpty();

        var resumo = repositorio.resumirComparacoes(
                idEmpresa,
                agora.minusSeconds(60),
                agora.plusSeconds(60));
        assertThat(resumo.totalComparacoes()).isEqualTo(1);
        assertThat(resumo.challengerMaisRestritivo()).isEqualTo(1);
        assertThat(resumo.mediaDiferencaPontuacao()).isEqualByComparingTo("50.0000000000000000");
    }

    @Test
    void deveAtualizarBancoExistenteDaVersao500ParaA600() {
        String esquema = "atualizacao_teste";
        var fonteDados = new DriverManagerDataSource(
                BANCO.getJdbcUrl(),
                BANCO.getUsername(),
                BANCO.getPassword());

        Flyway.configure()
                .dataSource(fonteDados)
                .schemas(esquema)
                .defaultSchema(esquema)
                .locations("classpath:db/migration/shared", "classpath:db/migration/service")
                .target(MigrationVersion.fromVersion("500"))
                .load()
                .migrate();

        var banco = JdbcClient.create(fonteDados);
        assertThat(banco.sql("""
                        SELECT COUNT(*)
                          FROM information_schema.tables
                         WHERE table_schema = :esquema
                           AND table_name = 'comparacao_modelos_risco'
                        """)
                .param("esquema", esquema)
                .query(Integer.class)
                .single()).isZero();

        Flyway.configure()
                .dataSource(fonteDados)
                .schemas(esquema)
                .defaultSchema(esquema)
                .locations("classpath:db/migration/shared", "classpath:db/migration/service")
                .load()
                .migrate();

        assertThat(banco.sql("""
                        SELECT version
                          FROM atualizacao_teste.flyway_schema_history
                         WHERE success
                         ORDER BY installed_rank DESC
                         LIMIT 1
                        """)
                .query(String.class)
                .single()).isEqualTo("600");
        assertThat(banco.sql("""
                        SELECT COUNT(*)
                          FROM information_schema.tables
                         WHERE table_schema = :esquema
                           AND table_name = 'comparacao_modelos_risco'
                        """)
                .param("esquema", esquema)
                .query(Integer.class)
                .single()).isEqualTo(1);
    }
}
