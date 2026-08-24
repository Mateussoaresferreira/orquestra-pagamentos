package br.com.orquestrapay.payment.api;

import java.util.List;

public record ResultadoConciliacao(
        int registrosAnalisados,
        int divergenciasEncontradas,
        List<String> divergencias) {
}
