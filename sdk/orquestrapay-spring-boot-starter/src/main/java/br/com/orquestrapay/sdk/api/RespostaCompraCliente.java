package br.com.orquestrapay.sdk.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record RespostaCompraCliente(
        UUID idCompra,
        UUID idEmpresa,
        String status,
        BigDecimal valorTotal,
        String moeda,
        MetodoPagamentoCliente metodoPagamento,
        int parcelas,
        String motivo,
        Instant criadoEm,
        Instant atualizadoEm) {
}
