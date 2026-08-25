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
        maximoPorJanela = maximoPorJanela <= 0 ? 60 : maximoPorJanela;
        janela = janela == null ? Duration.ofMinutes(1) : janela;
        maximoGlobalPorJanela = maximoGlobalPorJanela <= 0 ? 300 : maximoGlobalPorJanela;
        janelaGlobal = janelaGlobal == null ? Duration.ofSeconds(1) : janelaGlobal;
        maximoEmProcessamento = maximoEmProcessamento <= 0 ? 16 : maximoEmProcessamento;
    }
}
