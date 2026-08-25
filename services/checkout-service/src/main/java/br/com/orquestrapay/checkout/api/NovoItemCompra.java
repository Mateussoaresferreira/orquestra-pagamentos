package br.com.orquestrapay.checkout.api;

import java.math.BigDecimal;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record NovoItemCompra(
        @NotNull @Schema(example = "11111111-1111-1111-1111-111111111111") UUID idProduto,
        @Positive @Max(100_000) @Schema(example = "1") int quantidade,
        @NotNull @DecimalMin(value = "0.01") @Digits(integer = 10, fraction = 2)
        @Schema(example = "29.90") BigDecimal precoUnitario) {
}
