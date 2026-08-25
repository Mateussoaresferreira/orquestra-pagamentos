package br.com.orquestrapay.payment.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import br.com.orquestrapay.contracts.MetodoPagamento;

public record RespostaPagamento(
        UUID idPagamento,
        UUID idCompra,
        BigDecimal valor,
        String moeda,
        String status,
        String idAutorizacao,
        String motivo,
        MetodoPagamento metodoPagamento,
        int parcelas,
        String provedor,
        String txid,
        String copiaColaPix,
        String imagemQrCodeBase64,
        Instant expiraEm,
        Instant atualizadoEm) {

    public RespostaPagamento(
            UUID idPagamento,
            UUID idCompra,
            BigDecimal valor,
            String moeda,
            String status,
            String idAutorizacao,
            String motivo,
            Instant atualizadoEm) {
        this(
                idPagamento,
                idCompra,
                valor,
                moeda,
                status,
                idAutorizacao,
                motivo,
                MetodoPagamento.CARTAO,
                1,
                null,
                null,
                null,
                null,
                null,
                atualizadoEm);
    }
}
