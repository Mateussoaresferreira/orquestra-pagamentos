package br.com.orquestrapay.risk.service;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import br.com.orquestrapay.risk.data.RepositorioRisco;
import br.com.orquestrapay.risk.domain.ClassificacaoComparacaoRisco;
import br.com.orquestrapay.risk.domain.ContextoRisco;
import br.com.orquestrapay.risk.domain.ExperimentoModelosRisco;
import br.com.orquestrapay.risk.domain.ModeloRisco;
import br.com.orquestrapay.risk.domain.PoliticaRisco;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TesteServicoComparacaoModelosRisco {

    @Mock private RepositorioRisco repositorio;
    @Mock private MetricasModelosRisco metricas;

    @Test
    void devePersistirEContabilizarDivergenciaDoChallenger() {
        Instant agora = Instant.parse("2026-08-25T18:30:00Z");
        UUID idEmpresa = UUID.randomUUID();
        UUID idCompra = UUID.randomUUID();
        var contexto = new ContextoRisco(new BigDecimal("900.00"), "US", 0, 0);
        var modelos = modelos();
        var champion = modelos.avaliarChampion(contexto);
        var solicitacao = new SolicitacaoAvaliacaoSombra(
                idEmpresa, idCompra, contexto, champion);
        when(repositorio.adicionarComparacao(
                        org.mockito.ArgumentMatchers.eq(idEmpresa),
                        org.mockito.ArgumentMatchers.eq(idCompra),
                        org.mockito.ArgumentMatchers.eq(champion),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq(
                                ClassificacaoComparacaoRisco.CHALLENGER_MAIS_RESTRITIVO),
                        org.mockito.ArgumentMatchers.eq(agora)))
                .thenReturn(true);
        var servico = new ServicoComparacaoModelosRisco(
                repositorio,
                modelos,
                metricas,
                Clock.fixed(agora, ZoneOffset.UTC));

        servico.comparar(solicitacao);

        verify(metricas).registrarAvaliacao(
                org.mockito.ArgumentMatchers.eq("CHALLENGER"),
                org.mockito.ArgumentMatchers.any());
        verify(metricas).registrarComparacao(
                ClassificacaoComparacaoRisco.CHALLENGER_MAIS_RESTRITIVO,
                40);
    }

    @Test
    void naoDeveDuplicarMetricasQuandoComparacaoJaExiste() {
        var modelos = modelos();
        var contexto = new ContextoRisco(new BigDecimal("100.00"), "BR", 0, 0);
        var solicitacao = new SolicitacaoAvaliacaoSombra(
                UUID.randomUUID(),
                UUID.randomUUID(),
                contexto,
                modelos.avaliarChampion(contexto));
        var servico = new ServicoComparacaoModelosRisco(
                repositorio, modelos, metricas, Clock.systemUTC());

        servico.comparar(solicitacao);

        verify(metricas, never()).registrarComparacao(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    private ExperimentoModelosRisco modelos() {
        var champion = new ModeloRisco("regras", "1.0.0", politicaChampion());
        var challenger = new ModeloRisco("regras", "1.1.0", politicaChallenger());
        return new ExperimentoModelosRisco(champion, challenger, 100);
    }

    private PoliticaRisco politicaChampion() {
        return politica(1000, 15, 25, 70);
    }

    private PoliticaRisco politicaChallenger() {
        return politica(800, 20, 45, 65);
    }

    private PoliticaRisco politica(int limiteValor, int pontosValor, int pontosPais, int limite) {
        return new PoliticaRisco(
                new BigDecimal(limiteValor),
                new BigDecimal("5000.00"),
                pontosValor,
                50,
                "BR",
                pontosPais,
                3,
                Duration.ofMinutes(10),
                40,
                3,
                Duration.ofHours(24),
                45,
                limite);
    }
}
