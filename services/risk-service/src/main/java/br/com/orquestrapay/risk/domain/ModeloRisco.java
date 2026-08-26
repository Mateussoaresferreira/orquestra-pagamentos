package br.com.orquestrapay.risk.domain;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public final class ModeloRisco {

    private final String nome;
    private final String versao;
    private final PoliticaRisco politica;
    private final List<RegraRisco> regras;

    public ModeloRisco(String nome, String versao, PoliticaRisco politica) {
        this.nome = exigirIdentificador(nome, "nome");
        this.versao = exigirIdentificador(versao, "versao");
        this.politica = Objects.requireNonNull(politica, "politica");
        this.regras = List.of(
                new RegraRisco.RegraValor(politica),
                new RegraRisco.RegraPais(politica),
                new RegraRisco.RegraVelocidade(politica),
                new RegraRisco.RegraDispositivoCompartilhado(politica));
    }

    public ResultadoAvaliacaoRisco avaliar(ContextoRisco contexto) {
        Objects.requireNonNull(contexto, "contexto");
        List<SinalRisco> sinais = regras.stream()
                .map(regra -> regra.avaliar(contexto))
                .flatMap(java.util.Optional::stream)
                .toList();
        int pontuacao = Math.min(100, sinais.stream().mapToInt(SinalRisco::pontos).sum());
        boolean aprovada = pontuacao < politica.limiteReprovacao();
        String descricao = sinais.isEmpty()
                ? "Nenhum sinal de risco relevante"
                : sinais.stream()
                        .map(sinal -> sinal.codigo() + ": " + sinal.descricao())
                        .collect(Collectors.joining(" | "));
        return new ResultadoAvaliacaoRisco(
                nome, versao, pontuacao, aprovada, sinais, descricao);
    }

    public String nome() {
        return nome;
    }

    public String versao() {
        return versao;
    }

    public PoliticaRisco politica() {
        return politica;
    }

    private static String exigirIdentificador(String valor, String campo) {
        String normalizado = Objects.requireNonNull(valor, campo).trim();
        if (!normalizado.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,79}")) {
            throw new IllegalArgumentException(
                    campo + " deve ser um identificador de ate 80 caracteres");
        }
        return normalizado;
    }
}
