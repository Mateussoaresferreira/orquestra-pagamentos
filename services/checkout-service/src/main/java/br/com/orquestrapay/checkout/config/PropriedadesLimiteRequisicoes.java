package br.com.orquestrapay.checkout.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("orquestrapay.limite-requisicoes")
public record PropriedadesLimiteRequisicoes(
        boolean habilitado,
        int maximoPorJanela,
        Duration janela,
        int maximoGlobalPorJanela,
        Duration janelaGlobal,
        boolean permitirSemRedis,
        int maximoEmProcessamento) {

    public PropriedadesLimiteRequisicoes {
        maximoPorJanela = maximoPorJanela == 0 ? 60 : maximoPorJanela;
        janela = janela == null ? Duration.ofMinutes(1) : janela;
        maximoGlobalPorJanela = maximoGlobalPorJanela == 0 ? 300 : maximoGlobalPorJanela;
        janelaGlobal = janelaGlobal == null ? Duration.ofSeconds(1) : janelaGlobal;
        maximoEmProcessamento = maximoEmProcessamento == 0 ? 16 : maximoEmProcessamento;
        validarLimite(maximoPorJanela, 1_000_000, "maximoPorJanela");
        validarDuracao(janela, "janela");
        validarLimite(maximoGlobalPorJanela, 1_000_000, "maximoGlobalPorJanela");
        validarDuracao(janelaGlobal, "janelaGlobal");
        validarLimite(maximoEmProcessamento, 10_000, "maximoEmProcessamento");
    }

    private static void validarLimite(int valor, int maximo, String nome) {
        if (valor < 1 || valor > maximo) {
            throw new IllegalArgumentException(nome + " deve ficar entre 1 e " + maximo);
        }
    }

    private static void validarDuracao(Duration valor, String nome) {
        if (valor.isZero() || valor.isNegative()) {
            throw new IllegalArgumentException(nome + " deve ser maior que zero");
        }
    }
}
