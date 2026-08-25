package br.com.orquestrapay.platform.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class TesteIntegracaoRegistroMensagens {

    @Container
    private static final PostgreSQLContainer BANCO = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("mensagens_teste")
            .withUsername("teste")
            .withPassword("teste");

    private static JdbcClient banco;
    private static RegistroMensagens mensagens;
    private static TransactionTemplate transacao;

    @BeforeAll
    static void prepararBanco() {
        DataSource fonteDados = new DriverManagerDataSource(
                BANCO.getJdbcUrl(), BANCO.getUsername(), BANCO.getPassword());
        Flyway.configure()
                .dataSource(fonteDados)
                .locations("classpath:db/migration/shared")
                .load()
                .migrate();

        banco = JdbcClient.create(fonteDados);
        mensagens = new RegistroMensagens(banco, Clock.systemUTC());
        transacao = new TransactionTemplate(new DataSourceTransactionManager(fonteDados));
    }

    @BeforeEach
    void limparBanco() {
        banco.sql("TRUNCATE evento_processado").update();
    }

    @Test
    void deveProcessarUmaRedeliveryApenasUmaVez() {
        UUID idEvento = UUID.randomUUID();

        assertThat(mensagens.iniciar(idEvento, "servico-pagamento")).isTrue();
        assertThat(mensagens.iniciar(idEvento, "servico-pagamento")).isFalse();
        assertThat(totalRegistros()).isEqualTo(1);
    }

    @Test
    void devePermitirQueConsumidoresDiferentesProcessemOMesmoEvento() {
        UUID idEvento = UUID.randomUUID();

        assertThat(mensagens.iniciar(idEvento, "servico-checkout")).isTrue();
        assertThat(mensagens.iniciar(idEvento, "servico-notificacao")).isTrue();
        assertThat(totalRegistros()).isEqualTo(2);
    }

    @Test
    void deveLiberarNovaTentativaQuandoARegraDeNegocioSofreRollback() {
        UUID idEvento = UUID.randomUUID();

        assertThatThrownBy(() -> transacao.executeWithoutResult(estado -> {
            assertThat(mensagens.iniciar(idEvento, "servico-razao")).isTrue();
            throw new IllegalStateException("interrupcao simulada antes do commit");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(totalRegistros()).isZero();
        assertThat(mensagens.iniciar(idEvento, "servico-razao")).isTrue();
        assertThat(totalRegistros()).isEqualTo(1);
    }

    @Test
    void deveConterDuasInstanciasConcorrentesDoMesmoConsumidor() throws Exception {
        UUID idEvento = UUID.randomUUID();
        CountDownLatch inicio = new CountDownLatch(1);

        try (var instancias = Executors.newFixedThreadPool(2)) {
            var primeira = instancias.submit(() -> {
                inicio.await();
                return mensagens.iniciar(idEvento, "servico-estoque");
            });
            var segunda = instancias.submit(() -> {
                inicio.await();
                return mensagens.iniciar(idEvento, "servico-estoque");
            });

            inicio.countDown();

            assertThat(java.util.List.of(primeira.get(), segunda.get()))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(totalRegistros()).isEqualTo(1);
        }
    }

    private int totalRegistros() {
        return banco.sql("SELECT COUNT(*) FROM evento_processado")
                .query(Integer.class)
                .single();
    }
}
