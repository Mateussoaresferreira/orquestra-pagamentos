package br.com.orquestrapay.payment.messaging;

import static br.com.orquestrapay.contracts.TiposEventos.AUTORIZAR_PAGAMENTO;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.orquestrapay.contracts.EventoSaga;
import br.com.orquestrapay.contracts.ExcecaoVersaoEventoNaoSuportada;
import br.com.orquestrapay.payment.service.ServicoPagamento;
import org.junit.jupiter.api.Test;

class TesteConsumidorComandosPagamento {

    @Test
    void rejeitaVersaoDesconhecidaAntesDeAutorizarPagamento() {
        var pagamentos = mock(ServicoPagamento.class);
        var evento = mock(EventoSaga.class);
        when(evento.getTipo()).thenReturn(AUTORIZAR_PAGAMENTO);
        when(evento.getVersao()).thenReturn(99);

        assertThatThrownBy(() -> new ConsumidorComandosPagamento(pagamentos).receber(evento))
                .isInstanceOf(ExcecaoVersaoEventoNaoSuportada.class);
        verifyNoInteractions(pagamentos);
    }
}
