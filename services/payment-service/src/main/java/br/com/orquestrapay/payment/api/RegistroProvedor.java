package br.com.orquestrapay.payment.api;

import java.math.BigDecimal;
import java.time.Instant;
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
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String moeda,
        @NotBlank @Size(max = 30)
        @Pattern(regexp = "AUTORIZADO|RECUSADO|ESTORNADO") String status,
        @NotBlank @Size(max = 100) String idTransacaoProvedor,
        @NotNull Instant ocorridoEm) {
}
