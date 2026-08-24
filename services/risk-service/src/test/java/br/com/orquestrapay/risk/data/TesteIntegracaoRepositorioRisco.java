package br.com.orquestrapay.risk.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.flywaydb.core.Flyway;
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
                .extracting(resposta -> resposta.pontuacao(), resposta -> resposta.aprovada())
                .containsExactly(25, true);
        assertThat(repositorio.buscar(outraEmpresa, segundaCompra)).isEmpty();
    }
}
