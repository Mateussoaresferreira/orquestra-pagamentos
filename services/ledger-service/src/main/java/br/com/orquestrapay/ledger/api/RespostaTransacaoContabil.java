package br.com.orquestrapay.ledger.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RespostaTransacaoContabil(
        UUID idTransacao,
        UUID idCompra,
        UUID idPagamento,
        BigDecimal valor,
        String moeda,
        String status,
        String motivo,
        Instant criadaEm,
        List<Lancamento> lancamentos) {
}
