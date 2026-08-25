package br.com.orquestrapay.sdk.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record RespostaPagamentoCliente(
        UUID idPagamento,
        UUID idCompra,
        BigDecimal valor,
        String moeda,
        String status,
        String idAutorizacao,
        String motivo,
        MetodoPagamentoCliente metodoPagamento,
        int parcelas,
        String provedor,
        String txid,
        String copiaColaPix,
        String imagemQrCodeBase64,
        Instant expiraEm,
        Instant atualizadoEm) {
}
