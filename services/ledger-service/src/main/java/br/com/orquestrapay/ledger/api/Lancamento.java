package br.com.orquestrapay.ledger.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record Lancamento(
        UUID idLancamento,
        String conta,
        String natureza,
        BigDecimal valor,
        String moeda,
        Instant criadoEm) {
}
