package br.com.orquestrapay.payment.integration;

import java.util.List;

public record ResultadoRoteamento<T>(
        String provedor,
        T resposta,
        List<String> provedoresTentados) {

    public ResultadoRoteamento {
        provedoresTentados = List.copyOf(provedoresTentados);
    }
}
