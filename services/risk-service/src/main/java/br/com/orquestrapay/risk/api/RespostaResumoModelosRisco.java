package br.com.orquestrapay.risk.api;

import java.math.BigDecimal;
import java.time.Instant;

public record RespostaResumoModelosRisco(
        Instant desde,
        Instant ate,
        long totalComparacoes,
        long decisoesConcordantes,
        long challengerMaisRestritivo,
        long challengerMaisPermissivo,
        BigDecimal mediaDiferencaPontuacao) {
}
