package br.com.orquestrapay.payment.api;

import java.time.Instant;

public record RespostaCobrancaPixProvedor(
        String txid,
        String copiaCola,
        String imagemQrCodeBase64,
        Instant expiraEm,
        String status) {
}
