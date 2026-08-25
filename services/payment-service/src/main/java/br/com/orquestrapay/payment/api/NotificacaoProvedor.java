package br.com.orquestrapay.payment.api;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record NotificacaoProvedor(
        @NotNull UUID idEvento,
        @NotNull UUID idCompra,
        @NotBlank @Size(max = 100) String txid,
        @NotBlank @Pattern(regexp = "CONFIRMADO|EXPIRADO|DEVOLVIDO") String status,
        @NotNull Instant ocorridoEm) {
}
