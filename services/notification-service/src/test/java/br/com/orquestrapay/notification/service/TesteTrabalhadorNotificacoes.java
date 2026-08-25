package br.com.orquestrapay.notification.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import br.com.orquestrapay.notification.config.PropriedadesNotificacoes;
import br.com.orquestrapay.notification.data.RepositorioNotificacoes.NotificacaoPendente;
import org.junit.jupiter.api.Test;

class TesteTrabalhadorNotificacoes {

    @Test
    void confirmaTodoOLoteProcessadoEmParalelo() {
        var fila = mock(ServicoFilaNotificacoes.class);
        var primeira = notificacao("primeira@exemplo.com");
        var segunda = notificacao("segunda@exemplo.com");
        when(fila.reivindicar()).thenReturn(List.of(primeira, segunda));

        var propriedades = new PropriedadesNotificacoes(
                50,
                8,
                5,
                Duration.ofSeconds(30),
                Duration.ofSeconds(2),
                Duration.ofMinutes(10));

        new TrabalhadorNotificacoes(fila, propriedades).enviarPendentes();

        verify(fila).confirmar(primeira.idNotificacao());
        verify(fila).confirmar(segunda.idNotificacao());
    }

    private NotificacaoPendente notificacao(String destinatario) {
        return new NotificacaoPendente(
                UUID.randomUUID(),
                destinatario,
                "Compra concluida",
                "Sua compra foi concluida com sucesso",
                0);
    }
}
