package br.com.orquestrapay.checkout.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import br.com.orquestrapay.checkout.domain.Compra;
import br.com.orquestrapay.checkout.domain.StatusCompra;
import br.com.orquestrapay.contracts.ItemCompra;
import br.com.orquestrapay.platform.security.PropriedadesCriptografia;
import br.com.orquestrapay.platform.security.ProtecaoTokenPagamento;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class TesteIntegracaoRepositorioCompras {

    @Container
    private static final PostgreSQLContainer BANCO = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("checkout_teste")
            .withUsername("teste")
            .withPassword("teste");

    private static JdbcClient banco;
    private static RepositorioCompras repositorio;
    private static ProtecaoTokenPagamento protecaoToken;

    @BeforeAll
    static void prepararBanco() {
        var fonteDados = new DriverManagerDataSource(BANCO.getJdbcUrl(), BANCO.getUsername(), BANCO.getPassword());
        Flyway.configure()
                .dataSource(fonteDados)
                .locations("classpath:db/migration/shared", "classpath:db/migration/service")
                .load()
                .migrate();
        banco = JdbcClient.create(fonteDados);
        String chave = Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        protecaoToken = new ProtecaoTokenPagamento(new PropriedadesCriptografia(chave));
        repositorio = new RepositorioCompras(banco, protecaoToken);
    }

    @Test
    void devePersistirCompraComTokenProtegidoEIdempotencia() {
        UUID idCompra = UUID.randomUUID();
        UUID idEmpresa = UUID.randomUUID();
        UUID idProduto = UUID.randomUUID();
        Instant agora = Instant.parse("2026-08-23T12:00:00Z");
        var compra = new Compra(
                idCompra,
                idEmpresa,
                "cliente-42",
                "cliente@exemplo.com",
                "BRL",
                "BR",
                "dispositivo-42",
                new BigDecimal("199.80"),
                StatusCompra.RECEBIDA,
                UUID.randomUUID(),
                null,
                null,
                false,
                false,
                null,
                agora,
                agora,
                List.of(new ItemCompra(idProduto, 2, new BigDecimal("99.90"))));

        repositorio.adicionar(compra, "tok_sensivel_42", "checkout-42", "a".repeat(64));

        String valorPersistido = banco.sql("SELECT token_pagamento FROM compra WHERE id_compra = :idCompra")
                .param("idCompra", idCompra)
                .query(String.class)
                .single();
        assertThat(valorPersistido).startsWith("v1:").doesNotContain("tok_sensivel_42");

        var compraRecuperada = repositorio.buscar(idEmpresa, idCompra).orElseThrow();
        assertThat(compraRecuperada.itens()).containsExactlyElementsOf(compra.itens());
        String tokenProtegido = repositorio.buscarTokenProtegido(idEmpresa, idCompra).orElseThrow();
        assertThat(tokenProtegido).isEqualTo(valorPersistido);
        assertThat(protecaoToken.revelar(tokenProtegido, idCompra)).isEqualTo("tok_sensivel_42");
        assertThat(repositorio.buscarIdempotencia(idEmpresa, "checkout-42").orElseThrow().idCompra())
                .isEqualTo(idCompra);
        assertThat(repositorio.buscarParaAtualizacao(idEmpresa, idCompra)).isPresent();
        assertThat(repositorio.buscarParaAtualizacao(UUID.randomUUID(), idCompra)).isEmpty();
    }

    @Test
    void deveTratarTentativaDeInjecaoSqlSomenteComoDado() {
        UUID idCompra = UUID.randomUUID();
        UUID idEmpresa = UUID.randomUUID();
        String entradaHostil = "cliente'; DROP TABLE compra; --";
        String chaveHostil = "chave-' OR '1'='1";
        Instant agora = Instant.parse("2026-08-23T13:00:00Z");
        var compra = new Compra(
                idCompra,
                idEmpresa,
                entradaHostil,
                "seguranca@exemplo.com",
                "BRL",
                "BR",
                "<script>alert('teste')</script>",
                new BigDecimal("19.90"),
                StatusCompra.RECEBIDA,
                UUID.randomUUID(),
                null,
                null,
                false,
                false,
                null,
                agora,
                agora,
                List.of(new ItemCompra(UUID.randomUUID(), 1, new BigDecimal("19.90"))));

        repositorio.adicionar(compra, "tok_teste_injecao", chaveHostil, "b".repeat(64));

        assertThat(repositorio.buscar(idEmpresa, idCompra).orElseThrow().idCliente())
                .isEqualTo(entradaHostil);
        assertThat(repositorio.buscarIdempotencia(idEmpresa, chaveHostil)).isPresent();
        assertThat(banco.sql("SELECT COUNT(*) FROM compra WHERE id_compra = :idCompra")
                .param("idCompra", idCompra)
                .query(Long.class)
                .single()).isEqualTo(1);
    }
}
