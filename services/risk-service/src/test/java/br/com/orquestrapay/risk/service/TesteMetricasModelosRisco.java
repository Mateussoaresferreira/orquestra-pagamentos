package br.com.orquestrapay.risk.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import br.com.orquestrapay.risk.domain.ClassificacaoComparacaoRisco;
import br.com.orquestrapay.risk.domain.ResultadoAvaliacaoRisco;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class TesteMetricasModelosRisco {

    @Test
    void deveRegistrarAvaliacoesComparacoesEDesviosSemAltaCardinalidade() {
        var registro = new SimpleMeterRegistry();
        var metricas = new MetricasModelosRisco(registro);
        var resultado = new ResultadoAvaliacaoRisco(
                "regras-transacionais",
                "1.1.0",
                80,
                false,
                List.of(),
                "Compra reprovada");

        metricas.registrarAvaliacao("CHALLENGER", resultado);
        metricas.registrarComparacao(
                ClassificacaoComparacaoRisco.CHALLENGER_MAIS_RESTRITIVO,
                -15);
        metricas.registrarFalhaSombra();

        assertThat(registro.find("orquestrapay.risco.avaliacoes")
                .tag("papel", "CHALLENGER")
                .tag("modelo", "regras-transacionais")
                .tag("versao", "1.1.0")
                .tag("decisao", "REPROVADA")
                .counter())
                .isNotNull()
                .extracting(contador -> contador.count())
                .isEqualTo(1.0);
        assertThat(registro.find("orquestrapay.risco.comparacoes")
                .tag("classificacao", "CHALLENGER_MAIS_RESTRITIVO")
                .counter().count()).isEqualTo(1.0);
        assertThat(registro.find(
                        "orquestrapay.risco.comparacoes.diferenca_absoluta_pontuacao")
                .summary().max()).isEqualTo(15.0);
        assertThat(registro.find("orquestrapay.risco.avaliacao_sombra.falhas")
                .counter().count()).isEqualTo(1.0);
    }
}
