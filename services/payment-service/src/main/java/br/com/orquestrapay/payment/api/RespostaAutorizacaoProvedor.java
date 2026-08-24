package br.com.orquestrapay.payment.api;

public record RespostaAutorizacaoProvedor(
        boolean aprovada,
        String idAutorizacao,
        String motivo) {
}
