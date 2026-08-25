package br.com.orquestrapay.payment.api;

import java.time.Instant;
import java.util.UUID;

public record RespostaConciliacaoResumo(
        UUID idConciliacao,
        String provedor,
        String identificadorExtrato,
        Instant periodoInicio,
        Instant periodoFim,
        int registrosProvedor,
        int registrosLocais,
        int registrosDuplicados,
        int registrosAnalisados,
        int divergenciasEncontradas,
        String status,
        Instant concluidaEm) {
}
