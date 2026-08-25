package br.com.orquestrapay.notification.api;

import java.util.Set;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

public record ConfiguracaoWebhookEntrada(
        @NotBlank @Size(max = 2_048) String url,
        @NotBlank @Size(min = 32, max = 512) String segredo,
        @NotEmpty Set<@NotBlank String> eventos,
        boolean ativo) {

    public ConfiguracaoWebhookEntrada {
        eventos = eventos == null ? Set.of() : Set.copyOf(eventos);
    }
}
