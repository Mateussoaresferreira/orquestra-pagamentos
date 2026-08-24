package br.com.orquestrapay.notification.api;

import java.time.Instant;
import java.util.UUID;

public record RespostaNotificacao(
        UUID idNotificacao,
        UUID idCompra,
        String canal,
        String destinatario,
        String assunto,
        String status,
        int tentativas,
        Instant criadaEm,
        Instant enviadaEm) {
}
