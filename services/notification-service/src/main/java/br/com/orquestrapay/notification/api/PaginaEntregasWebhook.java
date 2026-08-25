package br.com.orquestrapay.notification.api;

import java.util.List;

public record PaginaEntregasWebhook(
        List<RespostaEntregaWebhook> itens,
        int pagina,
        int tamanho,
        long total) {

    public PaginaEntregasWebhook {
        itens = List.copyOf(itens);
    }
}
