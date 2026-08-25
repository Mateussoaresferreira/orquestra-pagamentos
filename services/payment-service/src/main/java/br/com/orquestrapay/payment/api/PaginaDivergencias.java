package br.com.orquestrapay.payment.api;

import java.util.List;

public record PaginaDivergencias(
        List<RespostaDivergencia> itens,
        int pagina,
        int tamanho,
        long total) {

    public PaginaDivergencias {
        itens = List.copyOf(itens);
    }
}
