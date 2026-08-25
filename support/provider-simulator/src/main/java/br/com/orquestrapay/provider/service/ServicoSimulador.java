package br.com.orquestrapay.provider.service;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import br.com.orquestrapay.provider.api.NotificacaoPagamento;
import br.com.orquestrapay.provider.api.PedidoAutorizacao;
import br.com.orquestrapay.provider.api.PedidoCobrancaPix;
import br.com.orquestrapay.provider.api.RespostaAutorizacao;
import br.com.orquestrapay.provider.api.RespostaCobrancaPix;
import br.com.orquestrapay.provider.api.RespostaEstorno;
import br.com.orquestrapay.provider.config.PropriedadesSimulador;
import br.com.orquestrapay.provider.security.AssinaturaWebhook;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ServicoSimulador {

    private final ConcurrentHashMap<UUID, AtomicInteger> tentativas = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, RespostaAutorizacao> autorizacoes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, RespostaEstorno> estornos = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CobrancaPix> pixPorCompra = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CobrancaPix> pixPorTxid = new ConcurrentHashMap<>();
    private final PropriedadesSimulador propriedades;
    private final RestClient http;
    private final ObjectMapper json;

    public ServicoSimulador(
            PropriedadesSimulador propriedades,
            RestClient clienteWebhook,
            ObjectMapper json) {
        this.propriedades = propriedades;
        this.http = clienteWebhook;
        this.json = json;
    }

    public RespostaAutorizacao autorizar(PedidoAutorizacao pedido) {
        var existente = autorizacoes.get(pedido.idCompra());
        if (existente != null) {
            return existente;
        }

        if ("tok_fallback".equals(pedido.tokenPagamento())
                && "principal".equals(propriedades.nome())) {
            indisponivel("Falha permanente do provedor principal simulada");
        }
        if ("tok_instavel".equals(pedido.tokenPagamento())) {
            int numero = tentativas
                    .computeIfAbsent(pedido.idCompra(), chave -> new AtomicInteger())
                    .incrementAndGet();
            if (numero <= 2) {
                indisponivel("Indisponibilidade temporaria simulada");
            }
        }

        RespostaAutorizacao resposta = switch (pedido.tokenPagamento()) {
            case "tok_recusado" -> new RespostaAutorizacao(
                    false,
                    null,
                    "Transacao recusada pelo emissor");
            default -> new RespostaAutorizacao(
                    true,
                    "aut_" + propriedades.nome() + "_" + identificador(),
                    "Pagamento autorizado em " + propriedades.nome());
        };
        autorizacoes.putIfAbsent(pedido.idCompra(), resposta);
        return autorizacoes.get(pedido.idCompra());
    }

    public RespostaCobrancaPix criarPix(PedidoCobrancaPix pedido) {
        validarUrlNotificacao(pedido.urlNotificacao());
        CobrancaPix existente = pixPorCompra.get(pedido.idCompra());
        if (existente != null) {
            return existente.resposta();
        }

        String txid = "pix" + identificador();
        var resposta = new RespostaCobrancaPix(
                txid,
                "000201ORQUESTRAPAY" + txid + "BRL" + pedido.valor().toPlainString(),
                null,
                Instant.now().plusSeconds(pedido.expiracaoSegundos()),
                "ATIVA");
        var cobranca = new CobrancaPix(
                pedido.idCompra(),
                pedido.urlNotificacao(),
                UUID.randomUUID(),
                resposta);
        CobrancaPix vencedora = pixPorCompra.putIfAbsent(pedido.idCompra(), cobranca);
        CobrancaPix armazenada = vencedora == null ? cobranca : vencedora;
        pixPorTxid.putIfAbsent(armazenada.resposta().txid(), armazenada);
        return armazenada.resposta();
    }

    public void confirmarPix(String txid) {
        CobrancaPix cobranca = pixPorTxid.get(txid);
        if (cobranca == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cobranca PIX nao encontrada");
        }
        var notificacao = new NotificacaoPagamento(
                cobranca.idEventoConfirmacao(),
                cobranca.idCompra(),
                txid,
                "CONFIRMADO",
                Instant.now());
        String conteudo;
        try {
            conteudo = json.writeValueAsString(notificacao);
        } catch (JacksonException excecao) {
            throw new IllegalStateException("Falha ao serializar notificacao PIX", excecao);
        }
        long timestamp = Instant.now().getEpochSecond();
        String assinatura = AssinaturaWebhook.assinar(
                propriedades.segredoWebhook(),
                timestamp + "." + conteudo);
        http.post()
                .uri(cobranca.urlNotificacao())
                .header("X-Provedor", propriedades.nome())
                .header("X-Orquestra-Timestamp", Long.toString(timestamp))
                .header("X-Orquestra-Signature", assinatura)
                .body(conteudo)
                .retrieve()
                .toBodilessEntity();
    }

    public RespostaEstorno estornar(UUID idPagamento) {
        return estornos.computeIfAbsent(idPagamento, chave -> new RespostaEstorno(
                true,
                "est_" + propriedades.nome() + "_" + identificador()));
    }

    private void validarUrlNotificacao(URI url) {
        String esquema = url.getScheme();
        if (!("http".equalsIgnoreCase(esquema) || "https".equalsIgnoreCase(esquema))
                || url.getUserInfo() != null
                || url.getFragment() != null
                || !propriedades.hostsNotificacaoPermitidos().contains(url.getHost())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "URL de notificacao nao permitida");
        }
    }

    private void indisponivel(String motivo) {
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, motivo);
    }

    private String identificador() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private record CobrancaPix(
            UUID idCompra,
            URI urlNotificacao,
            UUID idEventoConfirmacao,
            RespostaCobrancaPix resposta) {
    }
}
