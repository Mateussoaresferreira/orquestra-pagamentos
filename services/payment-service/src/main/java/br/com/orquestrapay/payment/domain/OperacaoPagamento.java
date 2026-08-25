package br.com.orquestrapay.payment.domain;

import java.math.BigDecimal;
import java.util.UUID;

import br.com.orquestrapay.contracts.MetodoPagamento;

public record OperacaoPagamento(
        UUID idOperacao,
        UUID idPagamento,
        UUID idEmpresa,
        UUID idCompra,
        TipoOperacaoPagamento tipo,
        MetodoPagamento metodoPagamento,
        BigDecimal valor,
        String moeda,
        String tokenProtegido,
        int parcelas,
        String provedor,
        int tentativas,
        UUID tokenBloqueio) {
}
