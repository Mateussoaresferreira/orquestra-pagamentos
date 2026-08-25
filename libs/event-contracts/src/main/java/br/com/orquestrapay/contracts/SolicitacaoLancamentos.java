package br.com.orquestrapay.contracts;

import java.math.BigDecimal;
import java.util.UUID;

public record SolicitacaoLancamentos(
        UUID idPagamento,
        BigDecimal valorTotal,
        String moeda,
        int parcelas) {

    public SolicitacaoLancamentos(UUID idPagamento, BigDecimal valorTotal, String moeda) {
        this(idPagamento, valorTotal, moeda, 1);
    }

    public SolicitacaoLancamentos {
        parcelas = parcelas == 0 ? 1 : parcelas;
        if (parcelas < 1 || parcelas > 12) {
            throw new IllegalArgumentException("A quantidade de parcelas deve ficar entre 1 e 12");
        }
    }
}
