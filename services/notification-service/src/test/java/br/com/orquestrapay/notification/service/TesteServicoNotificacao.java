package br.com.orquestrapay.notification.service;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import br.com.orquestrapay.contracts.CompraFinalizada;
import br.com.orquestrapay.contracts.EventoSaga;
import br.com.orquestrapay.notification.data.RepositorioNotificacoes;
import br.com.orquestrapay.platform.event.RegistroMensagens;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TesteServicoNotificacao {

    @Mock private RepositorioNotificacoes repositorio;
    @Mock private RegistroMensagens mensagens;
    @Mock private ObjectMapper json;
    @Mock private ServicoWebhooksEmpresa webhooks;

    @Test
    void deveAgendarMensagemAdequadaParaCompraConcluida() throws Exception {
        UUID idEvento = UUID.randomUUID();
        UUID idEmpresa = UUID.randomUUID();
        UUID idCompra = UUID.randomUUID();
        Instant agora = Instant.parse("2026-08-23T12:00:00Z");
        var evento = evento(idEvento, idEmpresa, idCompra, "conteudo-notificacao");
        var finalizacao = new CompraFinalizada(
                "CONCLUIDA", "Fluxo financeiro concluido", "cliente@exemplo.com");

        when(mensagens.iniciar(idEvento, "notificacao-v1")).thenReturn(true);
        when(json.readValue("conteudo-notificacao", CompraFinalizada.class)).thenReturn(finalizacao);
        var servico = new ServicoNotificacao(
                repositorio,
                mensagens,
                json,
                Clock.fixed(agora, ZoneOffset.UTC),
                webhooks);

        servico.agendar(evento);

        verify(repositorio).adicionar(
                idEvento,
                idEmpresa,
                idCompra,
                "cliente@exemplo.com",
                "Sua compra foi concluida",
                "Fluxo financeiro concluido",
                agora);
        verify(webhooks).agendar(evento, finalizacao);
    }

    @Test
    void naoDeveAgendarNovamenteUmEventoJaProcessado() {
        UUID idEvento = UUID.randomUUID();
        var evento = evento(idEvento, UUID.randomUUID(), UUID.randomUUID(), "conteudo");
        when(mensagens.iniciar(idEvento, "notificacao-v1")).thenReturn(false);
        var servico = new ServicoNotificacao(
                repositorio, mensagens, json, Clock.systemUTC(), webhooks);

        servico.agendar(evento);

        verify(repositorio, never()).adicionar(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        verify(webhooks, never()).agendar(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    private EventoSaga evento(UUID idEvento, UUID idEmpresa, UUID idCompra, String conteudo) {
        var evento = new EventoSaga();
        evento.setIdEvento(idEvento.toString());
        evento.setIdEmpresa(idEmpresa.toString());
        evento.setIdCompra(idCompra.toString());
        evento.setConteudo(conteudo);
        return evento;
    }
}
