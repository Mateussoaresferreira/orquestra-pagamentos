package br.com.orquestrapay.checkout.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import br.com.orquestrapay.checkout.domain.Compra;
import br.com.orquestrapay.contracts.MetodoPagamento;

public record RespostaCompra(
        UUID idCompra,
        UUID idEmpresa,
        String status,
        BigDecimal valorTotal,
        String moeda,
        MetodoPagamento metodoPagamento,
        int parcelas,
        String motivo,
        Instant criadoEm,
        Instant atualizadoEm) {

    public static RespostaCompra de(Compra compra) {
        return new RespostaCompra(
                compra.idCompra(),
                compra.idEmpresa(),
                compra.status().name(),
                compra.valorTotal(),
                compra.moeda(),
                compra.metodoPagamento(),
                compra.parcelas(),
                compra.motivo(),
                compra.criadoEm(),
                compra.atualizadoEm());
    }
}
