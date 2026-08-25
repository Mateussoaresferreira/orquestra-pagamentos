package br.com.orquestrapay.ledger.messaging;

import static br.com.orquestrapay.contracts.TiposEventos.REGISTRAR_LANCAMENTOS;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.orquestrapay.contracts.EventoSaga;
import br.com.orquestrapay.contracts.ExcecaoVersaoEventoNaoSuportada;
import br.com.orquestrapay.ledger.service.ServicoRazao;
import org.junit.jupiter.api.Test;

class TesteConsumidorLancamentos {

    @Test
    void rejeitaVersaoDesconhecidaAntesDeRegistrarLancamentos() {
        var razao = mock(ServicoRazao.class);
        var evento = mock(EventoSaga.class);
        when(evento.getTipo()).thenReturn(REGISTRAR_LANCAMENTOS);
        when(evento.getVersao()).thenReturn(99);

        assertThatThrownBy(() -> new ConsumidorLancamentos(razao).receber(evento))
                .isInstanceOf(ExcecaoVersaoEventoNaoSuportada.class);
        verifyNoInteractions(razao);
    }
}
