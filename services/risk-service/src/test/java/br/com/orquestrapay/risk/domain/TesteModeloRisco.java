package br.com.orquestrapay.risk.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class TesteModeloRisco {

    @Test
    void deveExplicarEPontuarDecisaoComVersaoDoModelo() {
        var modelo = new ModeloRisco("regras-transacionais", "1.1.0", politica(65));

        var resultado = modelo.avaliar(new ContextoRisco(
                new BigDecimal("4500.00"), "US", 3, 2));

        assertThat(resultado.modelo()).isEqualTo("regras-transacionais");
        assertThat(resultado.versao()).isEqualTo("1.1.0");
        assertThat(resultado.pontuacao()).isEqualTo(100);
        assertThat(resultado.aprovada()).isFalse();
        assertThat(resultado.sinais())
                .extracting(SinalRisco::codigo)
                .containsExactly(
                        "VALOR_ALTO",
                        "PAIS_DIVERGENTE",
                        "ALTA_VELOCIDADE",
                        "DISPOSITIVO_COMPARTILHADO");
        assertThat(resultado.descricao()).contains("VALOR_ALTO", "PAIS_DIVERGENTE");
    }

    @Test
    void deveRecusarIdentificadorDeModeloInvalido() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ModeloRisco("modelo com espaco", "1.0.0", politica(70)))
                .withMessageContaining("identificador");
    }

    @Test
    void deveSelecionarAmostraDeFormaDeterministica() {
        var champion = new ModeloRisco("regras", "1.0.0", politica(70));
        var challenger = new ModeloRisco("regras", "1.1.0", politica(65));
        var todos = new ExperimentoModelosRisco(champion, challenger, 100);
        var nenhum = new ExperimentoModelosRisco(champion, null, 0);
        UUID idCompra = UUID.randomUUID();

        assertThat(todos.deveAvaliarChallenger(idCompra)).isTrue();
        assertThat(todos.deveAvaliarChallenger(idCompra)).isTrue();
        assertThat(nenhum.deveAvaliarChallenger(idCompra)).isFalse();
        assertThat(todos.avaliarChallenger(contextoBasico())).isPresent();
        assertThat(nenhum.avaliarChallenger(contextoBasico())).isEmpty();
    }

    @Test
    void deveClassificarMudancaDeDecisao() {
        var aprovado = new ResultadoAvaliacaoRisco(
                "regras", "1.0.0", 30, true, java.util.List.of(), "Sem bloqueio");
        var reprovado = new ResultadoAvaliacaoRisco(
                "regras", "1.1.0", 80, false, java.util.List.of(), "Bloqueado");

        assertThat(ClassificacaoComparacaoRisco.comparar(aprovado, aprovado))
                .isEqualTo(ClassificacaoComparacaoRisco.DECISAO_CONCORDANTE);
        assertThat(ClassificacaoComparacaoRisco.comparar(aprovado, reprovado))
                .isEqualTo(ClassificacaoComparacaoRisco.CHALLENGER_MAIS_RESTRITIVO);
        assertThat(ClassificacaoComparacaoRisco.comparar(reprovado, aprovado))
                .isEqualTo(ClassificacaoComparacaoRisco.CHALLENGER_MAIS_PERMISSIVO);
    }

    @Test
    void deveValidarConfiguracaoDoExperimento() {
        var champion = new ModeloRisco("regras", "1.0.0", politica(70));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ExperimentoModelosRisco(champion, null, 1))
                .withMessageContaining("challenger");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ExperimentoModelosRisco(champion, champion, 100))
                .withMessageContaining("distintas");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ExperimentoModelosRisco(champion, null, 101))
                .withMessageContaining("percentual");
    }

    private ContextoRisco contextoBasico() {
        return new ContextoRisco(new BigDecimal("100.00"), "BR", 0, 0);
    }

    private PoliticaRisco politica(int limiteReprovacao) {
        return new PoliticaRisco(
                new BigDecimal("1000.00"),
                new BigDecimal("5000.00"),
                20,
                50,
                "BR",
                30,
                3,
                Duration.ofMinutes(10),
                40,
                2,
                Duration.ofHours(24),
                45,
                limiteReprovacao);
    }
}
