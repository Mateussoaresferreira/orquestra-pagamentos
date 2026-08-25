package br.com.orquestrapay.platform.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class TesteMetricasEventos {

    @Test
    void devePublicarRetratoAtualDoOutboxEContadoresOperacionais() {
        var repositorio = mock(RepositorioEventos.class);
        var registro = new SimpleMeterRegistry();
        var metricas = new MetricasEventos(repositorio, registro);
        when(repositorio.resumir()).thenReturn(new ResumoOutbox(7, 2, 31.5));

        metricas.atualizarOutbox();
        metricas.registrarPublicacao("eventos.teste");
        metricas.registrarFalhaPublicacao("eventos.teste");
        metricas.registrarDescarte("eventos.teste");
        metricas.registrarEnvioDlt("eventos.teste.dlt");

        assertThat(registro.get("orquestrapay.outbox.pendentes").gauge().value()).isEqualTo(7);
        assertThat(registro.get("orquestrapay.outbox.quarentena").gauge().value()).isEqualTo(2);
        assertThat(registro.get("orquestrapay.outbox.idade.mais.antiga.segundos").gauge().value())
                .isEqualTo(31.5);
        assertThat(registro.get("orquestrapay.outbox.publicados").counter().count()).isEqualTo(1);
        assertThat(registro.get("orquestrapay.outbox.falhas").counter().count()).isEqualTo(1);
        assertThat(registro.get("orquestrapay.outbox.descartados").counter().count()).isEqualTo(1);
        assertThat(registro.get("orquestrapay.dlt.eventos").counter().count()).isEqualTo(1);
    }

    @Test
    void devePreservarUltimoRetratoQuandoBancoFicarTemporariamenteIndisponivel() {
        var repositorio = mock(RepositorioEventos.class);
        var registro = new SimpleMeterRegistry();
        var metricas = new MetricasEventos(repositorio, registro);
        when(repositorio.resumir())
                .thenReturn(new ResumoOutbox(3, 0, 8.0))
                .thenThrow(new IllegalStateException("Banco indisponivel"));

        metricas.atualizarOutbox();

        assertThatCode(metricas::atualizarOutbox).doesNotThrowAnyException();
        assertThat(registro.get("orquestrapay.outbox.pendentes").gauge().value()).isEqualTo(3);
        assertThat(registro.get("orquestrapay.outbox.idade.mais.antiga.segundos").gauge().value())
                .isEqualTo(8.0);
    }
}
