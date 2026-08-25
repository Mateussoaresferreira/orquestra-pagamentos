package br.com.orquestrapay.notification.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import br.com.orquestrapay.notification.config.PropriedadesNotificacoes;
import br.com.orquestrapay.notification.data.RepositorioNotificacoes;
import org.junit.jupiter.api.Test;

class TesteServicoFilaNotificacoes {

    @Test
    void deveReivindicarSomenteOQueConsegueProcessarEmParalelo() {
        var repositorio = mock(RepositorioNotificacoes.class);
        var propriedades = new PropriedadesNotificacoes(
                "nao-responda@orquestrapay.local",
                50,
                8,
                5,
                Duration.ofSeconds(30),
                Duration.ofSeconds(2),
                Duration.ofMinutes(10));
        Instant agora = Instant.parse("2026-08-25T16:30:00Z");
        var fila = new ServicoFilaNotificacoes(
                repositorio,
                propriedades,
                Clock.fixed(agora, ZoneOffset.UTC));

        fila.reivindicar();

        verify(repositorio).reivindicarPendentes(
                8,
                5,
                agora,
                agora.plusSeconds(30));
    }
}
