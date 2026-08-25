package br.com.orquestrapay.notification.api;

import java.time.Instant;
import java.util.UUID;

public record RespostaEntregaWebhook(
        UUID idEntrega,
        UUID idEvento,
        UUID idCompra,
        String tipoEvento,
        String status,
        int tentativas,
        Integer ultimoStatusHttp,
        String ultimoErro,
        Instant criadaEm,
        Instant entregueEm,
        Instant falhaDefinitivaEm) {
}
