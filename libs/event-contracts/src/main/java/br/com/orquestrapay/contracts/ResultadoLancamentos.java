package br.com.orquestrapay.contracts;

import java.util.UUID;

public record ResultadoLancamentos(
        UUID idTransacaoContabil,
        boolean registrado,
        String motivo) {
}
