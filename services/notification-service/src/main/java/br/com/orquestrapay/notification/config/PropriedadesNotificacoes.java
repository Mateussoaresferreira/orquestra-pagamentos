package br.com.orquestrapay.notification.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("orquestrapay.notificacoes")
public record PropriedadesNotificacoes(
        int tamanhoLote,
        int concorrencia,
        int maximoTentativas,
        Duration duracaoBloqueio,
        Duration atrasoBase,
        Duration atrasoMaximo) {

    public PropriedadesNotificacoes {
        if (tamanhoLote < 1 || tamanhoLote > 500) {
            throw new IllegalArgumentException("O tamanho do lote deve estar entre 1 e 500");
        }
        if (concorrencia < 1 || concorrencia > 100) {
            throw new IllegalArgumentException("A concorrencia deve estar entre 1 e 100");
        }
        if (maximoTentativas < 1 || maximoTentativas > 20) {
            throw new IllegalArgumentException("O maximo de tentativas deve estar entre 1 e 20");
        }
        validarDuracao(duracaoBloqueio, "duracaoBloqueio");
        validarDuracao(atrasoBase, "atrasoBase");
        validarDuracao(atrasoMaximo, "atrasoMaximo");
    }

    private static void validarDuracao(Duration duracao, String nome) {
        if (duracao == null || duracao.isZero() || duracao.isNegative()) {
            throw new IllegalArgumentException(nome + " deve ser maior que zero");
        }
    }
}
