package br.com.orquestrapay.checkout.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import br.com.orquestrapay.contracts.ItemCompra;
import br.com.orquestrapay.contracts.MetodoPagamento;

public record Compra(
        UUID idCompra,
        UUID idEmpresa,
        String idCliente,
        String emailCliente,
        String moeda,
        String pais,
        String identificadorDispositivo,
        MetodoPagamento metodoPagamento,
        int parcelas,
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

    public Compra(
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
        this(
                idCompra,
                idEmpresa,
                idCliente,
                emailCliente,
                moeda,
                pais,
                identificadorDispositivo,
                MetodoPagamento.CARTAO,
                1,
                valorTotal,
                status,
                idReserva,
                idPagamento,
                idTransacaoContabil,
                pagamentoEstornado,
                estoqueLiberado,
                motivo,
                criadoEm,
                atualizadoEm,
                itens);
    }
}
