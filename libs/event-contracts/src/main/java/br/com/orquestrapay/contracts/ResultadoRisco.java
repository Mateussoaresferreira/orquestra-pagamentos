package br.com.orquestrapay.contracts;

public record ResultadoRisco(
        boolean aprovado,
        int pontuacao,
        String motivo) {
}
