package br.com.orquestrapay.contracts;

import java.util.UUID;

public record SolicitacaoCompensacao(
        UUID idReferencia,
        String motivo) {
}
