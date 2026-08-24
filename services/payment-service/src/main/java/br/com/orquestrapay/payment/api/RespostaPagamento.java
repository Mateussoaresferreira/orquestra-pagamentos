package br.com.orquestrapay.payment.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record RespostaPagamento(
        UUID idPagamento,
        UUID idCompra,
        BigDecimal valor,
        String moeda,
        String status,
        String idAutorizacao,
        String motivo,
        Instant atualizadoEm) {
}
