package br.com.orquestrapay.risk.config;

import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("orquestrapay.risco.modelos")
public record PropriedadesModelosRisco(
        String nomeChampion,
        String versaoChampion,
        boolean challengerHabilitado,
        String nomeChallenger,
        String versaoChallenger,
        int percentualAmostragem,
        PropriedadesPoliticaRisco politicaChallenger) {

    public PropriedadesModelosRisco {
        nomeChampion = exigirTexto(nomeChampion, "nomeChampion");
        versaoChampion = exigirTexto(versaoChampion, "versaoChampion");
        if (percentualAmostragem < 0 || percentualAmostragem > 100) {
            throw new IllegalArgumentException("O percentual de amostragem deve estar entre 0 e 100");
        }
        if (challengerHabilitado) {
            nomeChallenger = exigirTexto(nomeChallenger, "nomeChallenger");
            versaoChallenger = exigirTexto(versaoChallenger, "versaoChallenger");
            Objects.requireNonNull(politicaChallenger, "politicaChallenger");
        }
    }

    private static String exigirTexto(String valor, String campo) {
        String normalizado = Objects.requireNonNull(valor, campo).trim();
        if (normalizado.isEmpty()) {
            throw new IllegalArgumentException(campo + " e obrigatorio");
        }
        return normalizado;
    }
}
