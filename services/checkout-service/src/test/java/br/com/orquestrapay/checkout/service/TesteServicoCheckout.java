package br.com.orquestrapay.checkout.service;

import static br.com.orquestrapay.contracts.TiposEventos.ESTOQUE_RESERVADO;
import static br.com.orquestrapay.contracts.TiposEventos.RESERVAR_ESTOQUE;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import br.com.orquestrapay.checkout.data.RepositorioCompras;
import br.com.orquestrapay.contracts.EventoSaga;
import br.com.orquestrapay.platform.event.RegistroEventos;
import br.com.orquestrapay.platform.event.RegistroMensagens;
import br.com.orquestrapay.platform.security.ProtecaoTokenPagamento;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TesteServicoCheckout {

    @Mock private RepositorioCompras repositorio;
    @Mock private RegistroEventos eventos;
    @Mock private RegistroMensagens mensagens;
    @Mock private ObjectMapper json;
    @Mock private Clock relogio;
    @Mock private MeterRegistry metricas;
    @Mock private ProtecaoTokenPagamento protecaoToken;

    @Test
    void deveRejeitarEventoQuandoACompraNaoPertencerAEmpresaInformada() {
        UUID idEvento = UUID.randomUUID();
        UUID idEmpresa = UUID.randomUUID();
        UUID idCompra = UUID.randomUUID();
        var evento = new EventoSaga();
        evento.setIdEvento(idEvento.toString());
        evento.setIdEmpresa(idEmpresa.toString());
        evento.setIdCompra(idCompra.toString());
        evento.setTipo(ESTOQUE_RESERVADO);

        when(mensagens.iniciar(idEvento, "checkout-orquestrador-v1")).thenReturn(true);
        when(repositorio.buscarParaAtualizacao(idEmpresa, idCompra)).thenReturn(Optional.empty());
        var servico = new ServicoCheckout(
                repositorio, eventos, mensagens, json, relogio, metricas, protecaoToken);

        assertThatThrownBy(() -> servico.tratar(evento))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empresa informada");
        verify(repositorio).buscarParaAtualizacao(idEmpresa, idCompra);
    }

    @Test
    void deveIgnorarEventoQueFoiPublicadoPeloProprioCheckout() {
        var evento = new EventoSaga();
        evento.setTipo(RESERVAR_ESTOQUE);
        var servico = new ServicoCheckout(
                repositorio, eventos, mensagens, json, relogio, metricas, protecaoToken);

        servico.tratar(evento);

        verifyNoInteractions(repositorio, eventos, mensagens, json, relogio, metricas);
    }
}
