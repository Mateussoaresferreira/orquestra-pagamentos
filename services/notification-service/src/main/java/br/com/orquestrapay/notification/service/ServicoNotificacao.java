package br.com.orquestrapay.notification.service;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import br.com.orquestrapay.contracts.CompraFinalizada;
import br.com.orquestrapay.contracts.EventoSaga;
import br.com.orquestrapay.notification.api.RespostaNotificacao;
import br.com.orquestrapay.notification.data.RepositorioNotificacoes;
import br.com.orquestrapay.platform.event.RegistroMensagens;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ServicoNotificacao {

    private static final String CONSUMIDOR = "notificacao-v1";

    private final RepositorioNotificacoes repositorio;
    private final RegistroMensagens mensagens;
    private final ObjectMapper json;
    private final Clock relogio;
    private final ServicoWebhooksEmpresa webhooks;

    public ServicoNotificacao(
            RepositorioNotificacoes repositorio,
            RegistroMensagens mensagens,
            ObjectMapper json,
            Clock relogio,
            ServicoWebhooksEmpresa webhooks) {
        this.repositorio = repositorio;
        this.mensagens = mensagens;
        this.json = json;
        this.relogio = relogio;
        this.webhooks = webhooks;
    }

    @Transactional
    public void agendar(EventoSaga evento) {
        UUID idEvento = UUID.fromString(evento.getIdEvento());
        if (!mensagens.iniciar(idEvento, CONSUMIDOR)) {
            return;
        }

        CompraFinalizada finalizacao = ler(evento);
        String assunto = switch (finalizacao.status()) {
            case "CONCLUIDA" -> "Sua compra foi concluida";
            case "COMPENSADA" -> "Sua compra foi estornada";
            default -> "Nao foi possivel concluir sua compra";
        };
        repositorio.adicionar(
                idEvento,
                UUID.fromString(evento.getIdEmpresa()),
                UUID.fromString(evento.getIdCompra()),
                finalizacao.destinatario(),
                assunto,
                finalizacao.motivo(),
                relogio.instant());
        webhooks.agendar(evento, finalizacao);
    }

    @Transactional(readOnly = true)
    public List<RespostaNotificacao> buscar(UUID idEmpresa, UUID idCompra) {
        return repositorio.buscar(idEmpresa, idCompra);
    }

    private CompraFinalizada ler(EventoSaga evento) {
        try {
            return json.readValue(evento.getConteudo(), CompraFinalizada.class);
        } catch (JacksonException excecao) {
            throw new IllegalArgumentException("Payload de notificacao invalido", excecao);
        }
    }
}
