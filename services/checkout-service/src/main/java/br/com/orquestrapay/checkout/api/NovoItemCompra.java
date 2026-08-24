package br.com.orquestrapay.checkout.api;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record NovoItemCompra(
        @NotNull UUID idProduto,
        @Positive @Max(100_000) int quantidade,
        @NotNull @DecimalMin(value = "0.01") @Digits(integer = 10, fraction = 2)
        BigDecimal precoUnitario) {
}
