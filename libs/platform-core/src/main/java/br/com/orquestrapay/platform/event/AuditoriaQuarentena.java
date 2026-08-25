package br.com.orquestrapay.platform.event;

import java.time.Instant;
import java.util.UUID;

public record AuditoriaQuarentena(
        UUID idAuditoria,
        String acao,
        String responsavel,
        String detalhes,
        Integer tentativasAnteriores,
        String erroAnterior,
        String motivo,
        Instant registradaEm) {
}
