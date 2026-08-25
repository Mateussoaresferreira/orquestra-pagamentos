package br.com.orquestrapay.platform.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class TesteServicoFilaEventos {

    @Test
    void deveAplicarConcorrenciaPadraoCompativelComFluxosAssincronos() {
        var propriedades = new PropriedadesEventos(
                true,
                null,
                0,
                0,
                0,
                null,
                null,
                0,
                null,
                null);

        assertThat(propriedades.concorrenciaPublicacao()).isEqualTo(20);
        assertThat(propriedades.tamanhoLote()).isEqualTo(50);
    }

    @Test
    void deveReivindicarSomenteOQuePodeComecarAPublicarAntesDoLease() {
        var repositorio = mock(RepositorioEventos.class);
        var agora = Instant.parse("2026-08-25T15:00:00Z");
        var propriedades = new PropriedadesEventos(
                true,
                PropriedadesEventos.Topicos.padrao(),
                12,
                50,
                4,
                Duration.ofSeconds(10),
                Duration.ofSeconds(30),
                12,
                Duration.ofSeconds(1),
                Duration.ofMinutes(5));
        var fila = new ServicoFilaEventos(
                repositorio,
                propriedades,
                Clock.fixed(agora, ZoneOffset.UTC));

        fila.reivindicar();

        verify(repositorio).reivindicarPendentes(
                4,
                12,
                agora,
                agora.plusSeconds(30));
    }

    @Test
    void deveRecusarLeaseQuePodeVencerDuranteAPublicacao() {
        assertThatThrownBy(() -> new PropriedadesEventos(
                true,
                PropriedadesEventos.Topicos.padrao(),
                12,
                50,
                4,
                Duration.ofSeconds(10),
                Duration.ofSeconds(10),
                12,
                Duration.ofSeconds(1),
                Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bloqueio");
    }
}
