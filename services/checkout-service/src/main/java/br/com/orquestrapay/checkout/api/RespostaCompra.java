package br.com.orquestrapay.checkout.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import br.com.orquestrapay.checkout.domain.Compra;

public record RespostaCompra(
        UUID idCompra,
        UUID idEmpresa,
        String status,
        BigDecimal valorTotal,
        String moeda,
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
                compra.motivo(),
                compra.criadoEm(),
                compra.atualizadoEm());
    }
}
