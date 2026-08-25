package br.com.orquestrapay.provider.api;

import java.time.Instant;
import java.util.UUID;

public record NotificacaoPagamento(
        UUID idEvento,
        UUID idCompra,
        String txid,
        String status,
        Instant ocorridoEm) {
}
