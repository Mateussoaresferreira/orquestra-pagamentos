package br.com.orquestrapay.contracts;

import java.math.BigDecimal;

public record SolicitacaoPagamento(
        BigDecimal valorTotal,
        String moeda,
        String tokenPagamento) {
}
