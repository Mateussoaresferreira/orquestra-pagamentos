package br.com.orquestrapay.checkout.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import br.com.orquestrapay.contracts.ItemCompra;

public record Compra(
        UUID idCompra,
        UUID idEmpresa,
        String idCliente,
        String emailCliente,
        String moeda,
        String pais,
        String identificadorDispositivo,
        BigDecimal valorTotal,
        StatusCompra status,
        UUID idReserva,
        UUID idPagamento,
        UUID idTransacaoContabil,
        boolean pagamentoEstornado,
        boolean estoqueLiberado,
        String motivo,
        Instant criadoEm,
        Instant atualizadoEm,
        List<ItemCompra> itens) {
}
