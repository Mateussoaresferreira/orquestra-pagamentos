package br.com.orquestrapay.notification.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
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
class TesteIntegracaoRepositorioNotificacoes {

    @Container
    private static final PostgreSQLContainer BANCO = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("notificacao_teste")
            .withUsername("teste")
            .withPassword("teste");

    private static RepositorioNotificacoes repositorio;

    @BeforeAll
    static void prepararBanco() {
        var fonteDados = new DriverManagerDataSource(BANCO.getJdbcUrl(), BANCO.getUsername(), BANCO.getPassword());
        Flyway.configure()
                .dataSource(fonteDados)
                .locations("classpath:db/migration/shared", "classpath:db/migration/service")
                .load()
                .migrate();
        repositorio = new RepositorioNotificacoes(JdbcClient.create(fonteDados));
    }

    @Test
    void deveControlarTentativasEnvioEIsolarConsultaPorEmpresa() {
        UUID idEvento = UUID.randomUUID();
        UUID idEmpresa = UUID.randomUUID();
        UUID idCompra = UUID.randomUUID();
        Instant agora = Instant.parse("2026-08-23T12:00:00Z");

        repositorio.adicionar(
                idEvento,
                idEmpresa,
                idCompra,
                "cliente@exemplo.com",
                "Compra concluida",
                "Sua compra foi concluida com sucesso.",
                agora);

        var pendente = repositorio.bloquearPendentes(10).getFirst();
        assertThat(pendente.destinatario()).isEqualTo("cliente@exemplo.com");

        repositorio.registrarFalha(pendente.idNotificacao(), "Servidor SMTP indisponivel");
        var aposFalha = repositorio.buscar(idEmpresa, idCompra).getFirst();
        assertThat(aposFalha.status()).isEqualTo("PENDENTE");
        assertThat(aposFalha.tentativas()).isEqualTo(1);

        repositorio.marcarEnviada(pendente.idNotificacao(), agora.plusSeconds(30));
        var enviada = repositorio.buscar(idEmpresa, idCompra).getFirst();
        assertThat(enviada.status()).isEqualTo("ENVIADA");
        assertThat(enviada.tentativas()).isEqualTo(2);
        assertThat(enviada.enviadaEm()).isEqualTo(agora.plusSeconds(30));
        assertThat(repositorio.buscar(UUID.randomUUID(), idCompra)).isEmpty();
    }
}
