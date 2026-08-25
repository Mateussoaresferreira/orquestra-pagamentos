package br.com.orquestrapay.notification.messaging;

import static br.com.orquestrapay.contracts.TiposEventos.COMPRA_CONCLUIDA;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.orquestrapay.contracts.EventoSaga;
import br.com.orquestrapay.contracts.ExcecaoVersaoEventoNaoSuportada;
import br.com.orquestrapay.notification.service.ServicoNotificacao;
import org.junit.jupiter.api.Test;

class TesteConsumidorComprasFinalizadas {

    @Test
    void rejeitaVersaoDesconhecidaAntesDeAgendarNotificacao() {
        var notificacoes = mock(ServicoNotificacao.class);
        var evento = mock(EventoSaga.class);
        when(evento.getTipo()).thenReturn(COMPRA_CONCLUIDA);
        when(evento.getVersao()).thenReturn(99);

        assertThatThrownBy(() -> new ConsumidorComprasFinalizadas(notificacoes).receber(evento))
                .isInstanceOf(ExcecaoVersaoEventoNaoSuportada.class);
        verifyNoInteractions(notificacoes);
    }
}
