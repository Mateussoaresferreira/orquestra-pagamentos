package br.com.orquestrapay.contracts;

import java.math.BigDecimal;

public record SolicitacaoPagamento(
        BigDecimal valorTotal,
        String moeda,
        String tokenPagamento,
        MetodoPagamento metodoPagamento,
        int parcelas) {

    public SolicitacaoPagamento(BigDecimal valorTotal, String moeda, String tokenPagamento) {
        this(valorTotal, moeda, tokenPagamento, MetodoPagamento.CARTAO, 1);
    }

    public SolicitacaoPagamento {
        metodoPagamento = metodoPagamento == null ? MetodoPagamento.CARTAO : metodoPagamento;
        parcelas = parcelas == 0 ? 1 : parcelas;
    }
}
