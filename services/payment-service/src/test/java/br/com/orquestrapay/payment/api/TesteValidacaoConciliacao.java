package br.com.orquestrapay.payment.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TesteValidacaoConciliacao {

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
    void deveExigirAoMenosUmRegistro() {
        var violacoes = validador.validate(new PedidoConciliacao(null));

        assertThat(violacoes)
                .extracting(violacao -> violacao.getPropertyPath().toString())
                .containsExactly("registros");
    }

    @Test
    void deveLimitarAConciliacaoAQuinhentosRegistros() {
        var registro = registroValido();
        var pedido = new PedidoConciliacao(Collections.nCopies(501, registro));

        var violacoes = validador.validate(pedido);

        assertThat(violacoes)
                .extracting(violacao -> violacao.getPropertyPath().toString())
                .containsExactly("registros");
    }

    @Test
    void deveValidarCadaRegistroDoProvedor() {
        var registroInvalido = new RegistroProvedor(null, BigDecimal.ZERO, "PENDENTE");

        var violacoes = validador.validate(new PedidoConciliacao(List.of(registroInvalido)));

        assertThat(violacoes)
                .extracting(violacao -> violacao.getPropertyPath().toString())
                .containsExactlyInAnyOrder(
                        "registros[0].idPagamento",
                        "registros[0].valor",
                        "registros[0].status");
    }

    @Test
    void deveRejeitarElementoNuloNaLista() {
        var pedido = new PedidoConciliacao(Collections.singletonList(null));

        assertThat(validador.validate(pedido)).hasSize(1);
    }

    private RegistroProvedor registroValido() {
        return new RegistroProvedor(UUID.randomUUID(), new BigDecimal("49.90"), "AUTORIZADO");
    }
}
