package br.com.orquestrapay.risk.domain;

import java.util.List;

public record ResultadoAvaliacaoRisco(
        String modelo,
        String versao,
        int pontuacao,
        boolean aprovada,
        List<SinalRisco> sinais,
        String descricao) {

    public ResultadoAvaliacaoRisco {
        sinais = List.copyOf(sinais);
    }
}
