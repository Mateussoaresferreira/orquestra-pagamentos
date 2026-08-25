package br.com.orquestrapay.payment.api;

import java.time.Instant;
import java.util.UUID;

public record RespostaConciliacaoResumo(
        UUID idConciliacao,
        int registrosAnalisados,
        int divergenciasEncontradas,
        String status,
        Instant concluidaEm) {
}
