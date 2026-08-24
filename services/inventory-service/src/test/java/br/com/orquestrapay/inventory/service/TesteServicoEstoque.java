package br.com.orquestrapay.inventory.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import br.com.orquestrapay.contracts.EventoSaga;
import br.com.orquestrapay.contracts.SolicitacaoCompensacao;
import br.com.orquestrapay.inventory.data.RepositorioEstoque;
import br.com.orquestrapay.inventory.domain.StatusReserva;
import br.com.orquestrapay.platform.event.RegistroEventos;
import br.com.orquestrapay.platform.event.RegistroMensagens;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TesteServicoEstoque {

    @Mock private RepositorioEstoque repositorio;
    @Mock private RegistroEventos eventos;
    @Mock private RegistroMensagens mensagens;
    @Mock private ObjectMapper json;
    @Mock private Clock relogio;

    @Test
    void deveRecusarLiberacaoQuandoOEventoPertencerAOutraEmpresa() throws Exception {
        UUID idEvento = UUID.randomUUID();
        UUID idEmpresaEvento = UUID.randomUUID();
        UUID idCompra = UUID.randomUUID();
        UUID idReserva = UUID.randomUUID();
        var evento = new EventoSaga();
        evento.setIdEvento(idEvento.toString());
        evento.setIdEmpresa(idEmpresaEvento.toString());
        evento.setIdCompra(idCompra.toString());
        evento.setConteudo("conteudo-liberacao");

        when(mensagens.iniciar(idEvento, "estoque-v1")).thenReturn(true);
        when(json.readValue("conteudo-liberacao", SolicitacaoCompensacao.class))
                .thenReturn(new SolicitacaoCompensacao(idReserva, "compensacao"));
        when(repositorio.bloquearReserva(idReserva)).thenReturn(Optional.of(
                new RepositorioEstoque.Reserva(
                        idReserva,
                        UUID.randomUUID(),
                        idCompra,
                        StatusReserva.RESERVADA)));
        var servico = new ServicoEstoque(
                repositorio, eventos, mensagens, json, relogio);

        assertThatThrownBy(() -> servico.liberar(evento))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("O evento de liberacao nao pertence a reserva informada");
        verifyNoInteractions(eventos, relogio);
    }
}
