package br.com.orquestrapay.payment.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TesteValidacaoConciliacao {

    private static final Instant INICIO = Instant.parse("2026-08-23T00:00:00Z");
    private static final Instant FIM = INICIO.plusSeconds(3_600);
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
    void devePermitirExtratoVazioParaDetectarPagamentosAusentesNoProvedor() {
        var pedido = pedido(List.of());

        assertThat(validador.validate(pedido)).isEmpty();
    }

    @Test
    void deveExigirMetadadosDoExtrato() {
        var pedido = new PedidoConciliacao(null, null, null, null, null, List.of());

        assertThat(validador.validate(pedido))
                .extracting(violacao -> violacao.getPropertyPath().toString())
                .contains(
                        "provedor",
                        "identificadorExtrato",
                        "periodoInicio",
                        "periodoFim",
                        "moeda",
                        "periodoValido");
    }

    @Test
    void deveLimitarAConciliacaoAQuinhentosRegistros() {
        var pedido = pedido(Collections.nCopies(501, registroValido()));

        assertThat(validador.validate(pedido))
                .extracting(violacao -> violacao.getPropertyPath().toString())
                .containsExactly("registros");
    }

    @Test
    void deveValidarCadaRegistroDoProvedor() {
        var registroInvalido = new RegistroProvedor(
                null,
                BigDecimal.ZERO,
                "real",
                "PENDENTE",
                "",
                null);

        assertThat(validador.validate(pedido(List.of(registroInvalido))))
                .extracting(violacao -> violacao.getPropertyPath().toString())
                .containsExactlyInAnyOrder(
                        "registros[0].idPagamento",
                        "registros[0].valor",
                        "registros[0].moeda",
                        "registros[0].status",
                        "registros[0].idTransacaoProvedor",
                        "registros[0].ocorridoEm");
    }

    @Test
    void deveRejeitarElementoNuloNaLista() {
        var pedido = pedido(Collections.singletonList(null));

        assertThat(validador.validate(pedido)).hasSize(1);
    }

    @Test
    void deveRejeitarPeriodoInvertidoOuSuperiorATrintaEUmDias() {
        var invertido = new PedidoConciliacao(
                "principal",
                "extrato-invertido",
                FIM,
                INICIO,
                "BRL",
                List.of());
        var muitoAmplo = new PedidoConciliacao(
                "principal",
                "extrato-amplo",
                INICIO,
                INICIO.plusSeconds(32L * 24 * 60 * 60),
                "BRL",
                List.of());

        assertThat(validador.validate(invertido))
                .extracting(violacao -> violacao.getPropertyPath().toString())
                .containsExactly("periodoValido");
        assertThat(validador.validate(muitoAmplo))
                .extracting(violacao -> violacao.getPropertyPath().toString())
                .containsExactly("periodoValido");
    }

    private PedidoConciliacao pedido(List<RegistroProvedor> registros) {
        return new PedidoConciliacao(
                "principal",
                "extrato-001",
                INICIO,
                FIM,
                "BRL",
                registros);
    }

    private RegistroProvedor registroValido() {
        return new RegistroProvedor(
                UUID.randomUUID(),
                new BigDecimal("49.90"),
                "BRL",
                "AUTORIZADO",
                "aut-42",
                INICIO.plusSeconds(60));
    }
}
