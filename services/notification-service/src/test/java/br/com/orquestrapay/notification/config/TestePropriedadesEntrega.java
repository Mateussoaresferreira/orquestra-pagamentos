package br.com.orquestrapay.notification.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class TestePropriedadesEntrega {

    @Test
    void deveRecusarBackoffDeEmailInvertido() {
        assertThatThrownBy(() -> new PropriedadesNotificacoes(
                "nao-responda@orquestrapay.local",
                50,
                8,
                5,
                Duration.ofSeconds(30),
                Duration.ofSeconds(10),
                Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("atraso maximo");
    }

    @Test
    void deveRecusarLeaseMenorQueOTempoDaChamadaWebhook() {
        assertThatThrownBy(() -> new PropriedadesWebhooks(
                20,
                10,
                Duration.ofSeconds(7),
                Duration.ofSeconds(5),
                Duration.ofHours(1),
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bloqueio");
    }
}
