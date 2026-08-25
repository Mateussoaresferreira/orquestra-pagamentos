package br.com.orquestrapay.platform.event;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("orquestrapay.retencao-eventos")
public record PropriedadesRetencaoEventos(
        boolean habilitada,
        int tamanhoLote,
        Duration processados,
        Duration publicados,
        Duration quarentena) {

    public PropriedadesRetencaoEventos {
        tamanhoLote = tamanhoLote <= 0 ? 1_000 : tamanhoLote;
        processados = padraoValido(processados, Duration.ofDays(90), "processados");
        publicados = padraoValido(publicados, Duration.ofDays(7), "publicados");
        quarentena = padraoValido(quarentena, Duration.ofDays(365), "quarentena");
    }

    private static Duration padraoValido(Duration valor, Duration padrao, String nome) {
        Duration resultado = valor == null ? padrao : valor;
        if (resultado.isZero() || resultado.isNegative()) {
            throw new IllegalArgumentException(nome + " deve ser maior que zero");
        }
        return resultado;
    }
}
