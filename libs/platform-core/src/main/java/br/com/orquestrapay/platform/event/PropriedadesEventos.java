package br.com.orquestrapay.platform.event;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("orquestrapay.eventos")
public record PropriedadesEventos(
        boolean habilitado,
        String topico,
        int tamanhoLote,
        Duration tempoLimitePublicacao,
        int maximoTentativas,
        Duration atrasoBase,
        Duration atrasoMaximo) {

    public PropriedadesEventos {
        topico = topico == null || topico.isBlank() ? "orquestrapay.saga.v1" : topico;
        tamanhoLote = tamanhoLote <= 0 ? 50 : tamanhoLote;
        tempoLimitePublicacao = tempoLimitePublicacao == null
                ? Duration.ofSeconds(10)
                : tempoLimitePublicacao;
        maximoTentativas = maximoTentativas <= 0 ? 12 : maximoTentativas;
        atrasoBase = atrasoBase == null ? Duration.ofSeconds(1) : atrasoBase;
        atrasoMaximo = atrasoMaximo == null ? Duration.ofMinutes(5) : atrasoMaximo;
    }
}
