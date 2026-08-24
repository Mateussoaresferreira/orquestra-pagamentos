package br.com.orquestrapay.provider.api;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record PedidoAutorizacao(
        @NotNull UUID idCompra,
        @NotNull @Positive @Digits(integer = 17, fraction = 2) BigDecimal valor,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String moeda,
        @NotBlank @Size(max = 180) String tokenPagamento) {
}
