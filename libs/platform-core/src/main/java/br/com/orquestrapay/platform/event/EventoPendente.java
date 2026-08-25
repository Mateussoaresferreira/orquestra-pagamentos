package br.com.orquestrapay.platform.event;

import java.time.Instant;
import java.util.UUID;

public record EventoPendente(
        UUID idEvento,
        long ordem,
        String tipo,
        int versao,
        UUID idCorrelacao,
        UUID idEmpresa,
        UUID idCompra,
        String origem,
        String conteudo,
        String traceparent,
        Instant ocorridoEm,
        int tentativas,
        UUID tokenBloqueio) {
}
