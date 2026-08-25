package br.com.orquestrapay.notification.service;

import static br.com.orquestrapay.contracts.TiposEventos.COMPRA_COMPENSADA;
import static br.com.orquestrapay.contracts.TiposEventos.COMPRA_CONCLUIDA;
import static br.com.orquestrapay.contracts.TiposEventos.COMPRA_RECUSADA;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import br.com.orquestrapay.contracts.CompraFinalizada;
import br.com.orquestrapay.contracts.EventoSaga;
import br.com.orquestrapay.notification.api.ConfiguracaoWebhookEntrada;
import br.com.orquestrapay.notification.api.PaginaEntregasWebhook;
import br.com.orquestrapay.notification.api.RespostaConfiguracaoWebhook;
import br.com.orquestrapay.notification.data.RepositorioWebhooks;
import br.com.orquestrapay.platform.security.ProtecaoTokenPagamento;
import br.com.orquestrapay.platform.web.ExcecaoNegocio;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ServicoWebhooksEmpresa {

    private static final Set<String> EVENTOS_PERMITIDOS = Set.of(
            COMPRA_CONCLUIDA,
            COMPRA_RECUSADA,
            COMPRA_COMPENSADA);

    private final RepositorioWebhooks repositorio;
    private final ValidadorUrlWebhook validadorUrl;
    private final ProtecaoTokenPagamento protecaoSegredo;
    private final ObjectMapper json;
    private final Clock relogio;

    public ServicoWebhooksEmpresa(
            RepositorioWebhooks repositorio,
            ValidadorUrlWebhook validadorUrl,
            ProtecaoTokenPagamento protecaoSegredo,
            ObjectMapper json,
            Clock relogio) {
        this.repositorio = repositorio;
        this.validadorUrl = validadorUrl;
        this.protecaoSegredo = protecaoSegredo;
        this.json = json;
        this.relogio = relogio;
    }

    @Transactional
    public RespostaConfiguracaoWebhook configurar(
            UUID idEmpresa,
            ConfiguracaoWebhookEntrada entrada) {
        if (!EVENTOS_PERMITIDOS.containsAll(entrada.eventos())) {
            throw new ExcecaoNegocio(
                    HttpStatus.BAD_REQUEST,
                    "evento-webhook-invalido",
                    "Configure apenas eventos finais de compra suportados");
        }
        String url = validadorUrl.validar(entrada.url()).toString();
        Instant agora = relogio.instant();
        repositorio.salvarConfiguracao(
                idEmpresa,
                url,
                protecaoSegredo.proteger(entrada.segredo(), idEmpresa),
                entrada.eventos(),
                entrada.ativo(),
                agora);
        return new RespostaConfiguracaoWebhook(url, entrada.eventos(), entrada.ativo(), agora);
    }

    @Transactional(readOnly = true)
    public RespostaConfiguracaoWebhook buscarConfiguracao(UUID idEmpresa) {
        return repositorio.buscarConfiguracao(idEmpresa)
                .map(RepositorioWebhooks.Configuracao::resposta)
                .orElseThrow(() -> naoEncontrado("Configuracao de webhook nao encontrada"));
    }

    @Transactional
    public void desabilitar(UUID idEmpresa) {
        repositorio.desabilitar(idEmpresa, relogio.instant());
    }

    @Transactional
    public void agendar(EventoSaga evento, CompraFinalizada finalizacao) {
        UUID idEmpresa = UUID.fromString(evento.getIdEmpresa());
        var configuracao = repositorio.buscarConfiguracao(idEmpresa).orElse(null);
        if (configuracao == null
                || !configuracao.ativo()
                || !configuracao.eventos().contains(evento.getTipo())) {
            return;
        }
        Instant agora = relogio.instant();
        var conteudo = new EventoWebhookEmpresa(
                UUID.fromString(evento.getIdEvento()),
                evento.getTipo(),
                idEmpresa,
                UUID.fromString(evento.getIdCompra()),
                finalizacao.status(),
                finalizacao.motivo(),
                agora);
        try {
            repositorio.agendar(
                    idEmpresa,
                    conteudo.idEvento(),
                    conteudo.idCompra(),
                    conteudo.tipoEvento(),
                    json.writeValueAsString(conteudo),
                    agora);
        } catch (JacksonException excecao) {
            throw new IllegalStateException("Nao foi possivel criar o webhook", excecao);
        }
    }

    @Transactional(readOnly = true)
    public PaginaEntregasWebhook listar(UUID idEmpresa, int pagina, int tamanho) {
        return repositorio.listar(idEmpresa, pagina, tamanho);
    }

    @Transactional
    public void reprocessar(UUID idEmpresa, UUID idEntrega) {
        if (!repositorio.reprocessar(idEmpresa, idEntrega, relogio.instant())) {
            throw naoEncontrado("Entrega com falha definitiva nao encontrada");
        }
    }

    private ExcecaoNegocio naoEncontrado(String detalhe) {
        return new ExcecaoNegocio(HttpStatus.NOT_FOUND, "webhook-nao-encontrado", detalhe);
    }

    public record EventoWebhookEmpresa(
            UUID idEvento,
            String tipoEvento,
            UUID idEmpresa,
            UUID idCompra,
            String status,
            String motivo,
            Instant ocorridoEm) {
    }
}
