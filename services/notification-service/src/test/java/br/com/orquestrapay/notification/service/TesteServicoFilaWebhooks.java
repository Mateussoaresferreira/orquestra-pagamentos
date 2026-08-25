package br.com.orquestrapay.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import br.com.orquestrapay.notification.config.PropriedadesWebhooks;
import br.com.orquestrapay.notification.data.RepositorioWebhooks;
import br.com.orquestrapay.platform.security.ProtecaoTokenPagamento;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TesteServicoFilaWebhooks {

    @Mock private RepositorioWebhooks repositorio;
    @Mock private ProtecaoTokenPagamento protecaoSegredo;

    @Test
    void deveAplicarBackoffExponencialLimitado() {
        Instant agora = Instant.parse("2026-08-24T12:00:00Z");
        var servico = servico(10, Duration.ofSeconds(5), Duration.ofSeconds(12), agora);
        var entrega = entrega(4);
        when(repositorio.registrarFalha(
                        eq(entrega.dados()),
                        eq(503),
                        eq("indisponivel"),
                        any(),
                        eq(false),
                        eq(agora)))
                .thenReturn(true);

        servico.falhar(entrega, 503, "indisponivel");

        var proximaTentativa = ArgumentCaptor.forClass(Instant.class);
        verify(repositorio).registrarFalha(
                eq(entrega.dados()),
                eq(503),
                eq("indisponivel"),
                proximaTentativa.capture(),
                eq(false),
                eq(agora));
        assertThat(proximaTentativa.getValue()).isEqualTo(agora.plusSeconds(12));
    }

    @Test
    void deveEncerrarEntregaAoAtingirLimiteDeTentativas() {
        Instant agora = Instant.parse("2026-08-24T12:00:00Z");
        var metricas = new SimpleMeterRegistry();
        var servico = servico(3, Duration.ofSeconds(5), Duration.ofMinutes(1), agora, metricas);
        var entrega = entrega(3);
        when(repositorio.registrarFalha(
                        eq(entrega.dados()),
                        eq(500),
                        any(),
                        any(),
                        eq(true),
                        eq(agora)))
                .thenReturn(true);

        servico.falhar(entrega, 500, "falha final");

        verify(repositorio).registrarFalha(
                eq(entrega.dados()),
                eq(500),
                eq("falha final"),
                eq(agora.plusSeconds(20)),
                eq(true),
                eq(agora));
        assertThat(metricas.counter(
                        "orquestrapay.webhooks.entregas",
                        "resultado",
                        "falha_definitiva").count())
                .isEqualTo(1);
    }

    private ServicoFilaWebhooks servico(
            int maximoTentativas,
            Duration atrasoBase,
            Duration atrasoMaximo,
            Instant agora) {
        return servico(maximoTentativas, atrasoBase, atrasoMaximo, agora, new SimpleMeterRegistry());
    }

    private ServicoFilaWebhooks servico(
            int maximoTentativas,
            Duration atrasoBase,
            Duration atrasoMaximo,
            Instant agora,
            SimpleMeterRegistry metricas) {
        var propriedades = new PropriedadesWebhooks(
                20,
                maximoTentativas,
                Duration.ofSeconds(30),
                atrasoBase,
                atrasoMaximo,
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                false);
        return new ServicoFilaWebhooks(
                repositorio,
                propriedades,
                protecaoSegredo,
                Clock.fixed(agora, ZoneOffset.UTC),
                metricas);
    }

    private ServicoFilaWebhooks.EntregaWebhook entrega(int tentativas) {
        UUID idEmpresa = UUID.randomUUID();
        var dados = new RepositorioWebhooks.EntregaPendente(
                UUID.randomUUID(),
                idEmpresa,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "COMPRA_CONCLUIDA",
                "{}",
                "https://8.8.8.8/eventos",
                "segredo-protegido",
                tentativas,
                UUID.randomUUID());
        return new ServicoFilaWebhooks.EntregaWebhook(dados, "segredo");
    }
}
