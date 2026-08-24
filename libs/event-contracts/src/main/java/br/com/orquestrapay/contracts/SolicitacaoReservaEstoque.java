package br.com.orquestrapay.contracts;

import java.util.List;
import java.util.UUID;

public record SolicitacaoReservaEstoque(
        UUID idReserva,
        List<ItemCompra> itens) {

    public SolicitacaoReservaEstoque {
        itens = List.copyOf(itens);
    }
}
