package br.com.orquestrapay.sdk.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record LancamentoCliente(
        UUID idLancamento,
        String conta,
        String natureza,
        BigDecimal valor,
        String moeda,
        Instant criadoEm) {
}
