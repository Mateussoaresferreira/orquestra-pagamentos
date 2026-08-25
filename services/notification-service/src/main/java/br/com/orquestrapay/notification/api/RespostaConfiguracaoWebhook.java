package br.com.orquestrapay.notification.api;

import java.time.Instant;
import java.util.Set;

public record RespostaConfiguracaoWebhook(
        String url,
        Set<String> eventos,
        boolean ativo,
        Instant atualizadoEm) {

    public RespostaConfiguracaoWebhook {
        eventos = Set.copyOf(eventos);
    }
}
