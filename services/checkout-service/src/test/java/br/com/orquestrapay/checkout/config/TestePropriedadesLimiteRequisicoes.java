package br.com.orquestrapay.checkout.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class TestePropriedadesLimiteRequisicoes {

    @Test
    void deveRecusarLimiteNegativo() {
        assertThatThrownBy(() -> new PropriedadesLimiteRequisicoes(
                true,
                -1,
                Duration.ofMinutes(1),
                300,
                Duration.ofSeconds(1),
                false,
                16))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximoPorJanela");
    }

    @Test
    void deveRecusarJanelaNulaOuNegativa() {
        assertThatThrownBy(() -> new PropriedadesLimiteRequisicoes(
                true,
                60,
                Duration.ofSeconds(-1),
                300,
                Duration.ofSeconds(1),
                false,
                16))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("janela");
    }
}
