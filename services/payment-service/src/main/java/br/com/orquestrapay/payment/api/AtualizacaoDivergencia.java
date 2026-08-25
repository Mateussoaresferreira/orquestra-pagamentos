package br.com.orquestrapay.payment.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AtualizacaoDivergencia(
        @NotBlank @Pattern(regexp = "INVESTIGANDO|RESOLVIDA") String status,
        @Size(max = 2_000) String observacao) {
}
