package br.com.orquestrapay.notification.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("orquestrapay.notificacoes")
public record PropriedadesNotificacoes(
        String remetente,
        int tamanhoLote,
        int concorrencia,
        int maximoTentativas,
        Duration duracaoBloqueio,
        Duration atrasoBase,
        Duration atrasoMaximo) {

    public PropriedadesNotificacoes {
        remetente = remetente == null ? null : remetente.trim();
        if (remetente == null
                || remetente.isBlank()
                || !remetente.contains("@")
                || remetente.length() > 254) {
            throw new IllegalArgumentException("O remetente das notificacoes e invalido");
        }
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
        if (atrasoMaximo.compareTo(atrasoBase) < 0) {
            throw new IllegalArgumentException("O atraso maximo deve ser maior ou igual ao atraso base");
        }
    }

    private static void validarDuracao(Duration duracao, String nome) {
        if (duracao == null || duracao.isZero() || duracao.isNegative()) {
            throw new IllegalArgumentException(nome + " deve ser maior que zero");
        }
    }
}
