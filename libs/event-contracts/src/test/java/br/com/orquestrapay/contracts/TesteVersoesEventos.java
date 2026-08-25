package br.com.orquestrapay.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class TesteVersoesEventos {

    @Test
    void aceitaUmaDasVersoesDeclaradasPeloConsumidor() {
        var evento = mock(EventoSaga.class);
        when(evento.getTipo()).thenReturn(TiposEventos.ANALISAR_RISCO);
        when(evento.getVersao()).thenReturn(2);

        VersoesEventos.exigirSuportada(evento, 1, 2);
    }

    @Test
    void rejeitaVersaoDesconhecidaSemExporConteudoDoEvento() {
        var evento = mock(EventoSaga.class);
        when(evento.getTipo()).thenReturn(TiposEventos.ANALISAR_RISCO);
        when(evento.getVersao()).thenReturn(99);
        when(evento.getConteudo()).thenReturn("dado-sensivel");

        assertThatThrownBy(() -> VersoesEventos.exigirSuportada(evento, 1))
                .isInstanceOfSatisfying(
                        ExcecaoVersaoEventoNaoSuportada.class,
                        excecao -> {
                            assertThat(excecao.tipoEvento()).isEqualTo(TiposEventos.ANALISAR_RISCO);
                            assertThat(excecao.versaoRecebida()).isEqualTo(99);
                            assertThat(excecao.versoesSuportadas()).containsExactly(1);
                            assertThat(excecao.getMessage()).doesNotContain("dado-sensivel");
                        });
    }

    @Test
    void exigeDeclaracaoExplicitaDasVersoesAceitas() {
        var evento = mock(EventoSaga.class);
        when(evento.getTipo()).thenReturn("TESTE");
        when(evento.getVersao()).thenReturn(1);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> VersoesEventos.exigirSuportada(evento));
    }
}
