package br.com.orquestrapay.risk.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class ExperimentoModelosRisco {

    private final ModeloRisco champion;
    private final ModeloRisco challenger;
    private final int percentualAmostragem;

    public ExperimentoModelosRisco(
            ModeloRisco champion,
            ModeloRisco challenger,
            int percentualAmostragem) {
        this.champion = Objects.requireNonNull(champion, "champion");
        if (percentualAmostragem < 0 || percentualAmostragem > 100) {
            throw new IllegalArgumentException("O percentual de amostragem deve estar entre 0 e 100");
        }
        if (challenger == null && percentualAmostragem > 0) {
            throw new IllegalArgumentException("Amostragem exige um modelo challenger");
        }
        if (challenger != null
                && champion.nome().equals(challenger.nome())
                && champion.versao().equals(challenger.versao())) {
            throw new IllegalArgumentException("Champion e challenger devem possuir versoes distintas");
        }
        this.challenger = challenger;
        this.percentualAmostragem = percentualAmostragem;
    }

    public ResultadoAvaliacaoRisco avaliarChampion(ContextoRisco contexto) {
        return champion.avaliar(contexto);
    }

    public boolean deveAvaliarChallenger(UUID idCompra) {
        Objects.requireNonNull(idCompra, "idCompra");
        if (challenger == null || percentualAmostragem == 0) {
            return false;
        }
        int faixa = Math.floorMod(idCompra.hashCode(), 100);
        return faixa < percentualAmostragem;
    }

    public Optional<ResultadoAvaliacaoRisco> avaliarChallenger(ContextoRisco contexto) {
        return Optional.ofNullable(challenger).map(modelo -> modelo.avaliar(contexto));
    }

    public int percentualAmostragem() {
        return percentualAmostragem;
    }
}
