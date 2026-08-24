package br.com.orquestrapay.checkout.api;

import java.time.Instant;
import java.util.UUID;

public record RegistroHistorico(
        String etapa,
        String statusAnterior,
        String statusAtual,
        UUID idEvento,
        String detalhes,
        Instant registradoEm) {
}
