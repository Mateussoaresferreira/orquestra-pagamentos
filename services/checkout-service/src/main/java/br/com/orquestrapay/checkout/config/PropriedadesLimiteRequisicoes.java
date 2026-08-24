package br.com.orquestrapay.checkout.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("orquestrapay.limite-requisicoes")
public record PropriedadesLimiteRequisicoes(
        boolean habilitado,
        int maximoPorJanela,
        Duration janela,
        boolean permitirSemRedis) {

    public PropriedadesLimiteRequisicoes {
        maximoPorJanela = maximoPorJanela <= 0 ? 60 : maximoPorJanela;
        janela = janela == null ? Duration.ofMinutes(1) : janela;
    }
}
