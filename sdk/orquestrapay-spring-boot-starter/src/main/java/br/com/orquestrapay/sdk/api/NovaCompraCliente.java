package br.com.orquestrapay.sdk.api;

import java.util.List;

public record NovaCompraCliente(
        String idCliente,
        String emailCliente,
        String moeda,
        String pais,
        String identificadorDispositivo,
        String tokenPagamento,
        List<ItemCompraCliente> itens,
        MetodoPagamentoCliente metodoPagamento,
        int parcelas) {

    public NovaCompraCliente {
        itens = itens == null ? List.of() : List.copyOf(itens);
        metodoPagamento = metodoPagamento == null ? MetodoPagamentoCliente.CARTAO : metodoPagamento;
        parcelas = parcelas == 0 ? 1 : parcelas;
    }
}
