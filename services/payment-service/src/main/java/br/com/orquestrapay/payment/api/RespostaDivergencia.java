package br.com.orquestrapay.payment.api;

import java.time.Instant;
import java.util.UUID;

public record RespostaDivergencia(
        UUID idDivergencia,
        UUID idPagamento,
        String tipo,
        String detalhes,
        String status,
        String observacaoResolucao,
        Instant identificadaEm,
        Instant atualizadaEm,
        Instant resolvidaEm) {
}
