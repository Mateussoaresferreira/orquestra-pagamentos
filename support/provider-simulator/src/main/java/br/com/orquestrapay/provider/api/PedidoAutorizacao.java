package br.com.orquestrapay.provider.api;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record PedidoAutorizacao(
        @NotNull UUID idCompra,
        @NotNull @Positive @Digits(integer = 17, fraction = 2) BigDecimal valor,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String moeda,
        @NotBlank @Size(max = 180) String tokenPagamento,
        @Min(1) @Max(12) int parcelas) {

    public PedidoAutorizacao(
            UUID idCompra,
            BigDecimal valor,
            String moeda,
            String tokenPagamento) {
        this(idCompra, valor, moeda, tokenPagamento, 1);
    }
}
