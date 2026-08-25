package br.com.orquestrapay.notification.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("orquestrapay.webhooks")
public record PropriedadesWebhooks(
        int tamanhoLote,
        int maximoTentativas,
        Duration duracaoBloqueio,
        Duration atrasoBase,
        Duration atrasoMaximo,
        Duration tempoConexao,
        Duration tempoResposta,
        boolean permitirEnderecosPrivados) {

    public PropriedadesWebhooks {
        if (tamanhoLote < 1 || tamanhoLote > 100) {
            throw new IllegalArgumentException("O lote de webhooks deve ficar entre 1 e 100");
        }
        if (maximoTentativas < 1 || maximoTentativas > 20) {
            throw new IllegalArgumentException("O maximo de tentativas deve ficar entre 1 e 20");
        }
        validar(duracaoBloqueio, "duracaoBloqueio");
        validar(atrasoBase, "atrasoBase");
        validar(atrasoMaximo, "atrasoMaximo");
        validar(tempoConexao, "tempoConexao");
        validar(tempoResposta, "tempoResposta");
    }

    private static void validar(Duration valor, String nome) {
        if (valor == null || valor.isZero() || valor.isNegative()) {
            throw new IllegalArgumentException(nome + " deve ser maior que zero");
        }
    }
}
