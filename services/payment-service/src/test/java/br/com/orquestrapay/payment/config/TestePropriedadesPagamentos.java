package br.com.orquestrapay.payment.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class TestePropriedadesPagamentos {

    @Test
    void deveRecusarBackoffInvertido() {
        assertThatThrownBy(() -> new PropriedadesPagamentos.Trabalhador(
                20,
                6,
                Duration.ofSeconds(30),
                Duration.ofSeconds(10),
                Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("atraso maximo");
    }
}
