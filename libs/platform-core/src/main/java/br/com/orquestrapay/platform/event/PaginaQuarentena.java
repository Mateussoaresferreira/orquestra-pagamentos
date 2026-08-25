package br.com.orquestrapay.platform.event;

import java.util.List;

public record PaginaQuarentena(
        List<EventoQuarentena> itens,
        int pagina,
        int tamanho,
        long total) {

    public PaginaQuarentena {
        itens = List.copyOf(itens);
    }
}
