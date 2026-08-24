package br.com.orquestrapay.ledger.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import br.com.orquestrapay.ledger.domain.NaturezaLancamento;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class TesteIntegracaoRepositorioRazao {

    @Container
    private static final PostgreSQLContainer BANCO = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("razao_teste")
            .withUsername("teste")
            .withPassword("teste");

    private static JdbcClient banco;
    private static RepositorioRazao repositorio;

    @BeforeAll
    static void prepararBanco() {
        var fonteDados = new DriverManagerDataSource(BANCO.getJdbcUrl(), BANCO.getUsername(), BANCO.getPassword());
        Flyway.configure()
                .dataSource(fonteDados)
                .locations("classpath:db/migration/shared", "classpath:db/migration/service")
                .load()
                .migrate();
        banco = JdbcClient.create(fonteDados);
        repositorio = new RepositorioRazao(banco);
    }

    @Test
    void deveRegistrarPartidasDobradasEImpedirAlteracaoDosLancamentos() {
        UUID idEmpresa = UUID.randomUUID();
        UUID idCompra = UUID.randomUUID();
        UUID idPagamento = UUID.randomUUID();
        UUID idTransacao = UUID.randomUUID();
        Instant agora = Instant.parse("2026-08-23T12:00:00Z");

        repositorio.abrir(
                idTransacao,
                idEmpresa,
                idCompra,
                idPagamento,
                new BigDecimal("199.90"),
                "BRL",
                agora);
        repositorio.lancar(
                idTransacao,
                "ATIVO:ADQUIRENTE",
                NaturezaLancamento.DEBITO,
                new BigDecimal("199.90"),
                "BRL",
                agora);
        repositorio.lancar(
                idTransacao,
                "RECEITA:VENDAS",
                NaturezaLancamento.CREDITO,
                new BigDecimal("199.90"),
                "BRL",
                agora);
        repositorio.fechar(idTransacao);

        var transacao = repositorio.buscar(idEmpresa, idCompra).orElseThrow();
        assertThat(transacao.status()).isEqualTo("REGISTRADA");
        assertThat(transacao.lancamentos()).hasSize(2);
        assertThat(repositorio.buscar(UUID.randomUUID(), idCompra)).isEmpty();

        UUID idLancamento = transacao.lancamentos().getFirst().idLancamento();
        assertThatThrownBy(() -> banco.sql("DELETE FROM lancamento_contabil WHERE id_lancamento = :id")
                .param("id", idLancamento)
                .update())
                .hasMessageContaining("imutaveis");
    }

    @Test
    void deveRecusarFechamentoDeTransacaoDesbalanceadaNoBanco() {
        UUID idTransacao = UUID.randomUUID();
        repositorio.abrir(
                idTransacao,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal("50.00"),
                "BRL",
                Instant.parse("2026-08-23T13:00:00Z"));
        repositorio.lancar(
                idTransacao,
                "ATIVO:ADQUIRENTE",
                NaturezaLancamento.DEBITO,
                new BigDecimal("50.00"),
                "BRL",
                Instant.parse("2026-08-23T13:00:00Z"));

        assertThatThrownBy(() -> repositorio.fechar(idTransacao))
                .hasMessageContaining("desbalanceada");
    }
}
