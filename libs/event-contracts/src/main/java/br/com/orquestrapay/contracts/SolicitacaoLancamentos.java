package br.com.orquestrapay.contracts;

import java.math.BigDecimal;
import java.util.UUID;

public record SolicitacaoLancamentos(
        UUID idPagamento,
        BigDecimal valorTotal,
        String moeda) {
}
