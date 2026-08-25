package br.com.orquestrapay.notification.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

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
class TesteIntegracaoRepositorioNotificacoes {

    @Container
    private static final PostgreSQLContainer BANCO = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("notificacao_teste")
            .withUsername("teste")
            .withPassword("teste");

    private static RepositorioNotificacoes repositorio;
    private static JdbcClient banco;

    @BeforeAll
    static void prepararBanco() {
        var fonteDados = new DriverManagerDataSource(BANCO.getJdbcUrl(), BANCO.getUsername(), BANCO.getPassword());
        Flyway.configure()
                .dataSource(fonteDados)
                .locations("classpath:db/migration/shared", "classpath:db/migration/service")
                .load()
                .migrate();
        banco = JdbcClient.create(fonteDados);
        repositorio = new RepositorioNotificacoes(banco);
    }

    @BeforeEach
    void limpar() {
        banco.sql("TRUNCATE notificacao CASCADE").update();
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

        var pendente = repositorio.reivindicarPendentes(
                10, 5, agora, agora.plusSeconds(30)).getFirst();
        assertThat(pendente.destinatario()).isEqualTo("cliente@exemplo.com");
        assertThat(pendente.tentativas()).isEqualTo(1);
        assertThat(pendente.tokenBloqueio()).isNotNull();

        repositorio.registrarFalha(
                pendente,
                "Servidor SMTP indisponivel",
                agora.plusSeconds(10),
                null);
        var aposFalha = repositorio.buscar(idEmpresa, idCompra).getFirst();
        assertThat(aposFalha.status()).isEqualTo("PENDENTE");
        assertThat(aposFalha.tentativas()).isEqualTo(1);

        var segundaTentativa = repositorio.reivindicarPendentes(
                10, 5, agora.plusSeconds(11), agora.plusSeconds(41)).getFirst();
        assertThat(repositorio.marcarEnviada(segundaTentativa, agora.plusSeconds(30))).isTrue();
        var enviada = repositorio.buscar(idEmpresa, idCompra).getFirst();
        assertThat(enviada.status()).isEqualTo("ENVIADA");
        assertThat(enviada.tentativas()).isEqualTo(2);
        assertThat(enviada.enviadaEm()).isEqualTo(agora.plusSeconds(30));
        assertThat(repositorio.buscar(UUID.randomUUID(), idCompra)).isEmpty();
    }

    @Test
    void deveImpedirQueUmLeaseExpiradoConfirmeATentativaMaisNova() {
        UUID idEmpresa = UUID.randomUUID();
        UUID idCompra = UUID.randomUUID();
        Instant agora = Instant.parse("2026-08-23T12:00:00Z");
        repositorio.adicionar(
                UUID.randomUUID(),
                idEmpresa,
                idCompra,
                "cliente@exemplo.com",
                "Compra concluida",
                "Mensagem",
                agora);

        var leaseExpirado = repositorio.reivindicarPendentes(
                1, 5, agora, agora.plusSeconds(30)).getFirst();
        var leaseAtual = repositorio.reivindicarPendentes(
                1, 5, agora.plusSeconds(31), agora.plusSeconds(61)).getFirst();

        assertThat(leaseAtual.tokenBloqueio()).isNotEqualTo(leaseExpirado.tokenBloqueio());
        assertThat(repositorio.marcarEnviada(leaseExpirado, agora.plusSeconds(32))).isFalse();
        assertThat(repositorio.marcarEnviada(leaseAtual, agora.plusSeconds(33))).isTrue();
        assertThat(repositorio.buscar(idEmpresa, idCompra).getFirst().status()).isEqualTo("ENVIADA");
    }
}
