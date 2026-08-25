package br.com.orquestrapay.payment.api;

import java.math.BigDecimal;
import java.util.UUID;

public record PedidoCobrancaPixProvedor(
        UUID idCompra,
        BigDecimal valor,
        String moeda,
        int expiracaoSegundos,
        String urlNotificacao) {
}
