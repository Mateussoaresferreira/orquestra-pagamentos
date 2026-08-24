package br.com.orquestrapay.contracts;

import java.math.BigDecimal;
import java.util.UUID;

public record ItemCompra(
        UUID idProduto,
        int quantidade,
        BigDecimal precoUnitario) {

    public ItemCompra {
        if (idProduto == null) {
            throw new IllegalArgumentException("O produto e obrigatorio");
        }
        if (quantidade <= 0) {
            throw new IllegalArgumentException("A quantidade deve ser positiva");
        }
        if (precoUnitario == null || precoUnitario.signum() <= 0) {
            throw new IllegalArgumentException("O preco unitario deve ser positivo");
        }
    }

    public BigDecimal subtotal() {
        return precoUnitario.multiply(BigDecimal.valueOf(quantidade));
    }
}
