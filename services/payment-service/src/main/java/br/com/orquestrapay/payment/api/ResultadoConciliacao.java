package br.com.orquestrapay.payment.api;

import java.util.List;
import java.time.Instant;
import java.util.UUID;

public record ResultadoConciliacao(
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
        List<String> divergencias,
        String status,
        Instant concluidaEm,
        boolean reaproveitada) {

    public ResultadoConciliacao {
        divergencias = List.copyOf(divergencias);
    }
}
