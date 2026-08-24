package br.com.orquestrapay.contracts;

import java.util.UUID;

public record ResultadoPagamento(
        UUID idPagamento,
        String idAutorizacao,
        boolean aprovado,
        String motivo) {
}
