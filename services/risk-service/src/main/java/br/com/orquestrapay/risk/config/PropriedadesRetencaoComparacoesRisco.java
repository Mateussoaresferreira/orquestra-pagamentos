package br.com.orquestrapay.risk.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("orquestrapay.risco.retencao-comparacoes")
public record PropriedadesRetencaoComparacoesRisco(
        boolean habilitada,
        int tamanhoLote,
        Duration periodo) {

    public PropriedadesRetencaoComparacoesRisco {
        tamanhoLote = tamanhoLote <= 0 ? 1_000 : tamanhoLote;
        periodo = periodo == null ? Duration.ofDays(90) : periodo;
        if (periodo.isZero() || periodo.isNegative()) {
            throw new IllegalArgumentException("O periodo de retencao deve ser maior que zero");
        }
    }
}
