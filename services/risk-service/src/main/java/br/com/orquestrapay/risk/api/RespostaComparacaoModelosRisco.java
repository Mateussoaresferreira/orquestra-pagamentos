package br.com.orquestrapay.risk.api;

import java.time.Instant;
import java.util.UUID;

import br.com.orquestrapay.risk.domain.ClassificacaoComparacaoRisco;

public record RespostaComparacaoModelosRisco(
        UUID idComparacao,
        UUID idCompra,
        String modeloChampion,
        String versaoChampion,
        int pontuacaoChampion,
        boolean aprovadaChampion,
        String sinaisChampion,
        String modeloChallenger,
        String versaoChallenger,
        int pontuacaoChallenger,
        boolean aprovadaChallenger,
        String sinaisChallenger,
        ClassificacaoComparacaoRisco classificacao,
        int diferencaPontuacao,
        Instant avaliadaEm) {
}
