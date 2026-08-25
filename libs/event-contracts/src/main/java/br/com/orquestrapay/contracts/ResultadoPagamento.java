package br.com.orquestrapay.contracts;

import java.time.Instant;
import java.util.UUID;

public record ResultadoPagamento(
        UUID idPagamento,
        String idAutorizacao,
        boolean aprovado,
        String motivo,
        String status,
        MetodoPagamento metodoPagamento,
        String provedor,
        String txid,
        String copiaColaPix,
        Instant expiraEm) {

    public ResultadoPagamento(
            UUID idPagamento,
            String idAutorizacao,
            boolean aprovado,
            String motivo) {
        this(
                idPagamento,
                idAutorizacao,
                aprovado,
                motivo,
                aprovado ? "AUTORIZADO" : "RECUSADO",
                MetodoPagamento.CARTAO,
                null,
                null,
                null,
                null);
    }
}
