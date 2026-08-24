package br.com.orquestrapay.provider.api;

public record RespostaAutorizacao(
        boolean aprovada,
        String idAutorizacao,
        String motivo) {
}
