package br.com.orquestrapay.checkout.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class TesteValidacaoNovaCompra {

    private static jakarta.validation.ValidatorFactory fabrica;
    private static Validator validador;

    @BeforeAll
    static void prepararValidador() {
        fabrica = Validation.buildDefaultValidatorFactory();
        validador = fabrica.getValidator();
    }

    @AfterAll
    static void fecharValidador() {
        fabrica.close();
    }

    @Test
    void deveRejeitarListaDeItensNula() {
        var compra = novaCompra(null);

        var violacoes = validador.validate(compra);

        assertThat(violacoes)
                .extracting(violacao -> violacao.getPropertyPath().toString())
                .containsExactly("itens");
    }

    @Test
    void deveRejeitarListaDeItensVazia() {
        var compra = novaCompra(List.of());

        var violacoes = validador.validate(compra);

        assertThat(violacoes)
                .extracting(violacao -> violacao.getPropertyPath().toString())
                .containsExactly("itens");
    }

    @Test
    void deveRejeitarCodigoDeMoedaInexistente() {
        var compra = novaCompra("ZZZ", List.of(itemValido()));

        var violacoes = validador.validate(compra);

        assertThat(violacoes)
                .extracting(violacao -> violacao.getPropertyPath().toString())
                .containsExactly("moeda");
    }

    @Test
    void deveRejeitarSondasBooleanasDeSqlDaMesmaForma() {
        var primeiraSonda = validador.validate(novaCompra("John Doe AND 1=1 --", List.of(itemValido())));
        var segundaSonda = validador.validate(novaCompra("John Doe AND 1=2 --", List.of(itemValido())));

        assertThat(primeiraSonda)
                .extracting(violacao -> violacao.getPropertyPath().toString())
                .containsExactly("moeda");
        assertThat(segundaSonda)
                .extracting(violacao -> violacao.getPropertyPath().toString())
                .containsExactly("moeda");
        assertThat(primeiraSonda.iterator().next().getMessage())
                .isEqualTo(segundaSonda.iterator().next().getMessage());
    }

    @Test
    void deveAceitarMoedaComercialEIndicadorSemMoedaDaIso() {
        assertThat(validador.validate(novaCompra("BRL", List.of(itemValido())))).isEmpty();
        assertThat(validador.validate(novaCompra("XXX", List.of(itemValido())))).isEmpty();
    }

    @Test
    void deveAplicarCartaoEUmaParcelaQuandoCamposOpcionaisNaoForemEnviados() throws Exception {
        var idProduto = java.util.UUID.randomUUID();
        var json = """
                {
                  "idCliente": "cliente-legado",
                  "emailCliente": "cliente@exemplo.com",
                  "moeda": "BRL",
                  "pais": "BR",
                  "identificadorDispositivo": "dispositivo-001",
                  "tokenPagamento": "tok_aprovado",
                  "itens": [{
                    "idProduto": "%s",
                    "quantidade": 1,
                    "precoUnitario": 19.90
                  }]
                }
                """.formatted(idProduto);

        var compra = new ObjectMapper().readValue(json, NovaCompra.class);

        assertThat(compra.metodoPagamento()).isEqualTo(br.com.orquestrapay.contracts.MetodoPagamento.CARTAO);
        assertThat(compra.parcelas()).isEqualTo(1);
        assertThat(compra.itens()).hasSize(1);
    }

    @Test
    void deveRejeitarZeroParcelasInformadoExplicitamenteNoJson() throws Exception {
        var idProduto = java.util.UUID.randomUUID();
        var json = """
                {
                  "idCliente": "cliente-parcela-invalida",
                  "emailCliente": "cliente@exemplo.com",
                  "moeda": "BRL",
                  "pais": "BR",
                  "identificadorDispositivo": "dispositivo-001",
                  "tokenPagamento": "tok_aprovado",
                  "itens": [{
                    "idProduto": "%s",
                    "quantidade": 1,
                    "precoUnitario": 19.90
                  }],
                  "metodoPagamento": "CARTAO",
                  "parcelas": 0
                }
                """.formatted(idProduto);

        var compra = new ObjectMapper().readValue(json, NovaCompra.class);
        var violacoes = validador.validate(compra);

        assertThat(compra.parcelas()).isZero();
        assertThat(violacoes)
                .extracting(violacao -> violacao.getPropertyPath().toString())
                .containsExactly("parcelas");
    }

    @Test
    void deveRejeitarQuantidadeEPrecoForaDosLimitesDoBanco() {
        var item = new NovoItemCompra(
                java.util.UUID.randomUUID(),
                100_001,
                new java.math.BigDecimal("12345678901.999"));

        var violacoes = validador.validate(novaCompra(List.of(item)));

        assertThat(violacoes)
                .extracting(violacao -> violacao.getPropertyPath().toString())
                .contains("itens[0].quantidade", "itens[0].precoUnitario");
    }

    @Test
    void deveRejeitarCaracteresDeControleAntesDeAcessarOBanco() {
        var compra = new NovaCompra(
                "cliente\u0000invalido",
                "cliente\u0000@exemplo.com",
                "BRL",
                "BR",
                "dispositivo\u0000invalido",
                "token\u0000invalido",
                List.of(itemValido()),
                br.com.orquestrapay.contracts.MetodoPagamento.CARTAO,
                1);

        var violacoes = validador.validate(compra);

        assertThat(violacoes)
                .extracting(violacao -> violacao.getPropertyPath().toString())
                .contains(
                        "idCliente",
                        "emailCliente",
                        "identificadorDispositivo",
                        "tokenPagamento");
    }

    @Test
    void deveRejeitarEmailComSufixoInjetadoAntesDeCriarACompra() {
        var compra = new NovaCompra(
                "cliente-001",
                "cliente@exemplo.com'",
                "BRL",
                "BR",
                "dispositivo-001",
                "tok_teste_seguro",
                List.of(itemValido()));

        var violacoes = validador.validate(compra);

        assertThat(violacoes)
                .extracting(violacao -> violacao.getPropertyPath().toString())
                .containsExactly("emailCliente");
    }

    private NovaCompra novaCompra(List<NovoItemCompra> itens) {
        return novaCompra("BRL", itens);
    }

    private NovaCompra novaCompra(String moeda, List<NovoItemCompra> itens) {
        return new NovaCompra(
                "cliente-001",
                "cliente@exemplo.com",
                moeda,
                "BR",
                "dispositivo-001",
                "tok_teste_seguro",
                itens);
    }

    private NovoItemCompra itemValido() {
        return new NovoItemCompra(java.util.UUID.randomUUID(), 1, new java.math.BigDecimal("19.90"));
    }
}
