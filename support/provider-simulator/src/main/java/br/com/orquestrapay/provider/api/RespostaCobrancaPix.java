package br.com.orquestrapay.provider.api;

import java.time.Instant;

public record RespostaCobrancaPix(
        String txid,
        String copiaCola,
        String imagemQrCodeBase64,
        Instant expiraEm,
        String status) {
}
