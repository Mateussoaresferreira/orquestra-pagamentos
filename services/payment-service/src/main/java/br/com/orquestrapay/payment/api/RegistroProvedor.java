package br.com.orquestrapay.payment.api;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record RegistroProvedor(
        @NotNull UUID idPagamento,
        @NotNull @Positive @Digits(integer = 17, fraction = 2) BigDecimal valor,
        @NotBlank @Size(max = 30)
        @Pattern(regexp = "AUTORIZADO|RECUSADO|ESTORNADO") String status) {
}
