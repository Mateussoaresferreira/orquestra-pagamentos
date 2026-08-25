package br.com.orquestrapay.sdk.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ParcelaRecebivelCliente(
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
