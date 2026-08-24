package br.com.orquestrapay.checkout.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

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
    void deveAceitarMoedaComercialEIndicadorSemMoedaDaIso() {
        assertThat(validador.validate(novaCompra("BRL", List.of(itemValido())))).isEmpty();
        assertThat(validador.validate(novaCompra("XXX", List.of(itemValido())))).isEmpty();
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
