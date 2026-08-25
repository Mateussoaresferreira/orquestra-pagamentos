package br.com.orquestrapay.payment.api;

import java.math.BigDecimal;
import java.util.UUID;

public record PedidoAutorizacaoProvedor(
        UUID idCompra,
        BigDecimal valor,
        String moeda,
        String tokenPagamento,
        int parcelas) {

    public PedidoAutorizacaoProvedor(
            UUID idCompra,
            BigDecimal valor,
            String moeda,
            String tokenPagamento) {
        this(idCompra, valor, moeda, tokenPagamento, 1);
    }
}
