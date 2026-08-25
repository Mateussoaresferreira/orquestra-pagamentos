package br.com.orquestrapay.provider.api;

import java.math.BigDecimal;
import java.net.URI;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public record PedidoCobrancaPix(
        @NotNull UUID idCompra,
        @NotNull @Positive BigDecimal valor,
        @Pattern(regexp = "[A-Z]{3}") String moeda,
        @Min(60) @Max(86_400) int expiracaoSegundos,
        @NotNull URI urlNotificacao) {
}
