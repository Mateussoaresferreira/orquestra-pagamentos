package br.com.orquestrapay.checkout.messaging;

import static br.com.orquestrapay.contracts.TiposEventos.PAGAMENTO_AUTORIZADO;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.orquestrapay.checkout.service.ServicoCheckout;
import br.com.orquestrapay.contracts.EventoSaga;
import br.com.orquestrapay.contracts.ExcecaoVersaoEventoNaoSuportada;
import org.junit.jupiter.api.Test;

class TesteConsumidorSaga {

    @Test
    void rejeitaVersaoDesconhecidaAntesDeAlterarASaga() {
        var checkout = mock(ServicoCheckout.class);
        var evento = mock(EventoSaga.class);
        when(evento.getTipo()).thenReturn(PAGAMENTO_AUTORIZADO);
        when(evento.getVersao()).thenReturn(99);

        assertThatThrownBy(() -> new ConsumidorSaga(checkout).receber(evento))
                .isInstanceOf(ExcecaoVersaoEventoNaoSuportada.class);
        verifyNoInteractions(checkout);
    }
}
