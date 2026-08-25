package br.com.orquestrapay.sdk.api;

import java.math.BigDecimal;
import java.util.UUID;

public record ItemCompraCliente(
        UUID idProduto,
        int quantidade,
        BigDecimal precoUnitario) {
}
