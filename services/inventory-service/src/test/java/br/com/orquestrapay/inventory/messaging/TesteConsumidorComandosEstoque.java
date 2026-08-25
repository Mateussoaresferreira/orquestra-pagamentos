package br.com.orquestrapay.inventory.messaging;

import static br.com.orquestrapay.contracts.TiposEventos.RESERVAR_ESTOQUE;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.orquestrapay.contracts.EventoSaga;
import br.com.orquestrapay.contracts.ExcecaoVersaoEventoNaoSuportada;
import br.com.orquestrapay.inventory.service.ServicoEstoque;
import org.junit.jupiter.api.Test;

class TesteConsumidorComandosEstoque {

    @Test
    void rejeitaVersaoDesconhecidaAntesDeReservarEstoque() {
        var estoque = mock(ServicoEstoque.class);
        var evento = mock(EventoSaga.class);
        when(evento.getTipo()).thenReturn(RESERVAR_ESTOQUE);
        when(evento.getVersao()).thenReturn(99);

        assertThatThrownBy(() -> new ConsumidorComandosEstoque(estoque).receber(evento))
                .isInstanceOf(ExcecaoVersaoEventoNaoSuportada.class);
        verifyNoInteractions(estoque);
    }
}
