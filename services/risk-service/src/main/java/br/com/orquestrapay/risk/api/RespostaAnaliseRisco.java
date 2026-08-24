package br.com.orquestrapay.risk.api;

import java.time.Instant;
import java.util.UUID;

public record RespostaAnaliseRisco(
        UUID idAnalise,
        UUID idCompra,
        int pontuacao,
        boolean aprovada,
        String sinais,
        Instant analisadaEm) {
}
