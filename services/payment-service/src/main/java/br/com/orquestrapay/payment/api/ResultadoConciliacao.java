package br.com.orquestrapay.payment.api;

import java.util.List;
import java.time.Instant;
import java.util.UUID;

public record ResultadoConciliacao(
        int registrosAnalisados,
        int divergenciasEncontradas,
        List<String> divergencias,
        UUID idConciliacao,
        Instant concluidaEm) {

    public ResultadoConciliacao(
            int registrosAnalisados,
            int divergenciasEncontradas,
            List<String> divergencias) {
        this(registrosAnalisados, divergenciasEncontradas, divergencias, null, null);
    }

    public ResultadoConciliacao {
        divergencias = List.copyOf(divergencias);
    }
}
