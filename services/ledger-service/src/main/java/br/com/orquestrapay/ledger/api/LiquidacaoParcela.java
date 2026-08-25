package br.com.orquestrapay.ledger.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LiquidacaoParcela(
        @NotBlank @Size(max = 100) String referencia) {
}
