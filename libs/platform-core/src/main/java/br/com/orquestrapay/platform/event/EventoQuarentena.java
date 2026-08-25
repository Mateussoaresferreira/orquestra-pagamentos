package br.com.orquestrapay.platform.event;

import java.time.Instant;
import java.util.UUID;

public record EventoQuarentena(
        UUID idEvento,
        String tipo,
        int versao,
        UUID idCorrelacao,
        UUID idCompra,
        String origem,
        int tentativas,
        String ultimoErro,
        Instant ocorridoEm,
        Instant descartadoEm) {
}
