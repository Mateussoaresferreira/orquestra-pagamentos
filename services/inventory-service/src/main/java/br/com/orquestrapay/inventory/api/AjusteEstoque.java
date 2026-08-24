package br.com.orquestrapay.inventory.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record AjusteEstoque(
        @PositiveOrZero int quantidadeDisponivel,
        @NotBlank @Size(max = 200) String motivo) {
}
