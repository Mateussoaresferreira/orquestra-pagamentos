package br.com.orquestrapay.ledger.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ParcelaRecebivel(
        UUID idParcela,
        int numero,
        int totalParcelas,
        BigDecimal valor,
        LocalDate vencimento,
        String status,
        String referenciaLiquidacao,
        Instant criadaEm,
        Instant liquidadaEm) {
}
