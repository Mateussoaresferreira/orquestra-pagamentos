package br.com.orquestrapay.provider.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TesteValidacaoPedidoAutorizacao {

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
    void deveRejeitarDadosQueExcedemOContratoDoProvedor() {
        var pedido = new PedidoAutorizacao(
                UUID.randomUUID(),
                new BigDecimal("123456789012345678.999"),
                "real",
                "x".repeat(181));

        var violacoes = validador.validate(pedido);

        assertThat(violacoes)
                .extracting(violacao -> violacao.getPropertyPath().toString())
                .contains("valor", "moeda", "tokenPagamento");
    }
}
