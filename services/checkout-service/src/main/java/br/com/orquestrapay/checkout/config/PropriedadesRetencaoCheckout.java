package br.com.orquestrapay.checkout.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("orquestrapay.retencao-checkout")
public record PropriedadesRetencaoCheckout(
        boolean habilitada,
        int tamanhoLote,
        Duration chavesIdempotencia) {

    public PropriedadesRetencaoCheckout {
        tamanhoLote = tamanhoLote <= 0 ? 1_000 : tamanhoLote;
        chavesIdempotencia = chavesIdempotencia == null
                ? Duration.ofDays(90)
                : chavesIdempotencia;
        if (chavesIdempotencia.isZero() || chavesIdempotencia.isNegative()) {
            throw new IllegalArgumentException("chavesIdempotencia deve ser maior que zero");
        }
    }
}
