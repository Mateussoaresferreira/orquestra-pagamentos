package br.com.orquestrapay.risk.domain;

import java.math.BigDecimal;

public record ContextoRisco(
        BigDecimal valorTotal,
        String pais,
        int comprasRecentesCliente,
        int clientesRecentesNoDispositivo) {
}
