package br.com.orquestrapay.risk.messaging;

import static br.com.orquestrapay.contracts.TiposEventos.ANALISAR_RISCO;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.orquestrapay.contracts.EventoSaga;
import br.com.orquestrapay.contracts.ExcecaoVersaoEventoNaoSuportada;
import br.com.orquestrapay.risk.service.ServicoRisco;
import org.junit.jupiter.api.Test;

class TesteConsumidorAnaliseRisco {

    @Test
    void rejeitaVersaoDesconhecidaAntesDeAnalisarRisco() {
        var risco = mock(ServicoRisco.class);
        var evento = mock(EventoSaga.class);
        when(evento.getTipo()).thenReturn(ANALISAR_RISCO);
        when(evento.getVersao()).thenReturn(99);

        assertThatThrownBy(() -> new ConsumidorAnaliseRisco(risco).receber(evento))
                .isInstanceOf(ExcecaoVersaoEventoNaoSuportada.class);
        verifyNoInteractions(risco);
    }
}
