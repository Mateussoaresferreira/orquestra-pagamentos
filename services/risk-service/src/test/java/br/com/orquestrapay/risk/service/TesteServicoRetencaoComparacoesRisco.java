package br.com.orquestrapay.risk.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import br.com.orquestrapay.risk.config.PropriedadesRetencaoComparacoesRisco;
import br.com.orquestrapay.risk.data.RepositorioRisco;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TesteServicoRetencaoComparacoesRisco {

    private static final Instant AGORA = Instant.parse("2026-08-25T12:00:00Z");

    @Mock private RepositorioRisco repositorio;

    @Test
    void deveRemoverEmLoteERegistrarMetrica() {
        var propriedades = new PropriedadesRetencaoComparacoesRisco(
                true, 500, Duration.ofDays(90));
        var metricas = new SimpleMeterRegistry();
        var servico = new ServicoRetencaoComparacoesRisco(
                repositorio,
                propriedades,
                Clock.fixed(AGORA, ZoneOffset.UTC),
                metricas);
        Instant limite = AGORA.minus(Duration.ofDays(90));
        when(repositorio.removerComparacoesAnterioresA(limite, 500)).thenReturn(7);

        servico.executar();

        verify(repositorio).removerComparacoesAnterioresA(limite, 500);
        assertThat(metricas.counter("orquestrapay.risco.retencao.comparacoes.removidas").count())
                .isEqualTo(7);
    }

    @Test
    void naoDeveConsultarBancoQuandoRetencaoEstiverDesabilitada() {
        var servico = new ServicoRetencaoComparacoesRisco(
                repositorio,
                new PropriedadesRetencaoComparacoesRisco(
                        false, 500, Duration.ofDays(90)),
                Clock.fixed(AGORA, ZoneOffset.UTC),
                new SimpleMeterRegistry());

        servico.executar();

        verify(repositorio, never()).removerComparacoesAnterioresA(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt());
    }
}
