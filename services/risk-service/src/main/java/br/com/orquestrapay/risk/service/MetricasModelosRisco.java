package br.com.orquestrapay.risk.service;

import br.com.orquestrapay.risk.domain.ClassificacaoComparacaoRisco;
import br.com.orquestrapay.risk.domain.ResultadoAvaliacaoRisco;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class MetricasModelosRisco {

    private final MeterRegistry metricas;

    public MetricasModelosRisco(MeterRegistry metricas) {
        this.metricas = metricas;
    }

    public void registrarAvaliacao(String papel, ResultadoAvaliacaoRisco resultado) {
        metricas.counter(
                        "orquestrapay.risco.avaliacoes",
                        "papel", papel,
                        "modelo", resultado.modelo(),
                        "versao", resultado.versao(),
                        "decisao", resultado.aprovada() ? "APROVADA" : "REPROVADA")
                .increment();
    }

    public void registrarComparacao(
            ClassificacaoComparacaoRisco classificacao,
            int diferencaPontuacao) {
        metricas.counter(
                        "orquestrapay.risco.comparacoes",
                        "classificacao", classificacao.name())
                .increment();
        metricas.summary("orquestrapay.risco.comparacoes.diferenca_absoluta_pontuacao")
                .record(Math.abs(diferencaPontuacao));
    }

    public void registrarFalhaSombra() {
        metricas.counter("orquestrapay.risco.avaliacao_sombra.falhas").increment();
    }
}
