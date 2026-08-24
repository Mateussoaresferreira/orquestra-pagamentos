package br.com.orquestrapay.contracts;

import java.math.BigDecimal;

public record SolicitacaoAnaliseRisco(
        String idCliente,
        BigDecimal valorTotal,
        String pais,
        String identificadorDispositivo) {
}
