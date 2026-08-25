package br.com.orquestrapay.sdk.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RespostaTransacaoContabilCliente(
        UUID idTransacao,
        UUID idCompra,
        UUID idPagamento,
        BigDecimal valor,
        String moeda,
        String status,
        String motivo,
        Instant criadaEm,
        BigDecimal totalDebitos,
        BigDecimal totalCreditos,
        List<LancamentoCliente> lancamentos,
        List<ParcelaRecebivelCliente> parcelas) {
}
