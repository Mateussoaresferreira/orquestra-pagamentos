package br.com.orquestrapay.payment.service;

import static br.com.orquestrapay.contracts.TiposEventos.PAGAMENTO_AUTORIZADO;
import static br.com.orquestrapay.contracts.TiposEventos.PAGAMENTO_ESTORNADO;
import static br.com.orquestrapay.contracts.TiposEventos.PAGAMENTO_RECUSADO;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

import br.com.orquestrapay.contracts.ResultadoPagamento;
import br.com.orquestrapay.payment.api.NotificacaoProvedor;
import br.com.orquestrapay.payment.config.PropriedadesPagamentos;
import br.com.orquestrapay.payment.data.RepositorioOperacoesPagamento;
import br.com.orquestrapay.payment.data.RepositorioPagamentos;
import br.com.orquestrapay.payment.data.RepositorioWebhooksProvedor;
import br.com.orquestrapay.payment.domain.StatusPagamento;
import br.com.orquestrapay.payment.domain.TipoOperacaoPagamento;
import br.com.orquestrapay.payment.integration.CatalogoProvedores;
import br.com.orquestrapay.platform.event.RegistroEventos;
import br.com.orquestrapay.platform.security.AssinaturaHmac;
import br.com.orquestrapay.platform.web.ExcecaoNegocio;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Validator;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ServicoWebhookProvedor {

    private static final String ORIGEM = "servico-pagamento";

    private final CatalogoProvedores provedores;
    private final RepositorioWebhooksProvedor webhooks;
    private final RepositorioPagamentos pagamentos;
    private final RepositorioOperacoesPagamento operacoes;
    private final RegistroEventos eventos;
    private final PropriedadesPagamentos propriedades;
    private final ObjectMapper json;
    private final Clock relogio;
    private final MeterRegistry metricas;
    private final Validator validador;

    public ServicoWebhookProvedor(
            CatalogoProvedores provedores,
            RepositorioWebhooksProvedor webhooks,
            RepositorioPagamentos pagamentos,
            RepositorioOperacoesPagamento operacoes,
            RegistroEventos eventos,
            PropriedadesPagamentos propriedades,
            ObjectMapper json,
            Clock relogio,
            MeterRegistry metricas,
            Validator validador) {
        this.provedores = provedores;
        this.webhooks = webhooks;
        this.pagamentos = pagamentos;
        this.operacoes = operacoes;
        this.eventos = eventos;
        this.propriedades = propriedades;
        this.json = json;
        this.relogio = relogio;
        this.metricas = metricas;
        this.validador = validador;
    }

    @Transactional
    public void processar(
            String nomeProvedor,
            long timestamp,
            String assinatura,
            String conteudo) {
        var provedor = provedores.buscar(nomeProvedor)
                .orElseThrow(() -> problema("provedor-desconhecido", "Provedor desconhecido"));
        String nomeCanonico = provedor.nome();
        Instant agora = relogio.instant();
        Instant instanteAssinatura;
        try {
            instanteAssinatura = Instant.ofEpochSecond(timestamp);
        } catch (RuntimeException excecao) {
            throw problema("timestamp-invalido", "Timestamp do webhook invalido");
        }
        Duration diferenca = Duration.between(instanteAssinatura, agora).abs();
        if (diferenca.compareTo(propriedades.pix().toleranciaAssinatura()) > 0) {
            rejeitar(nomeCanonico, "timestamp-expirado");
            throw problema("webhook-expirado", "Webhook fora da janela de seguranca");
        }

        String baseAssinatura = timestamp + "." + conteudo;
        String esperada = AssinaturaHmac.assinar(provedor.segredoWebhook(), baseAssinatura);
        if (!AssinaturaHmac.corresponde(esperada, assinatura)) {
            rejeitar(nomeCanonico, "assinatura-invalida");
            throw problema("assinatura-invalida", "Assinatura do webhook invalida");
        }

        NotificacaoProvedor notificacao = ler(conteudo);
        validar(notificacao);
        var resultadoRegistro = webhooks.registrar(
                nomeCanonico,
                notificacao.idEvento(),
                calcularHash(conteudo),
                agora);
        if (resultadoRegistro == RepositorioWebhooksProvedor.ResultadoRegistro.DUPLICADO) {
            metricas.counter(
                    "orquestrapay.webhooks.provedor",
                    "provedor", nomeCanonico,
                    "resultado", "duplicado").increment();
            return;
        }
        if (resultadoRegistro == RepositorioWebhooksProvedor.ResultadoRegistro.CONFLITANTE) {
            metricas.counter(
                    "orquestrapay.webhooks.provedor",
                    "provedor", nomeCanonico,
                    "resultado", "conflitante").increment();
            throw new ExcecaoNegocio(
                    HttpStatus.CONFLICT,
                    "webhook-conflitante",
                    "O identificador do webhook ja foi usado com outro conteudo");
        }

        var pagamento = pagamentos.bloquearPorPix(nomeCanonico, notificacao.txid())
                .orElse(null);
        if (pagamento == null) {
            metricas.counter(
                    "orquestrapay.webhooks.provedor",
                    "provedor", nomeCanonico,
                    "resultado", "pagamento_ainda_indisponivel").increment();
            throw new ExcecaoNegocio(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "pagamento-webhook-indisponivel",
                    "O pagamento ainda nao esta disponivel; repita o webhook");
        }
        if (!pagamento.idCompra().equals(notificacao.idCompra())) {
            webhooks.concluir(
                    nomeCanonico,
                    notificacao.idEvento(),
                    null,
                    "IGNORADO",
                    "Pagamento PIX nao localizado",
                    agora);
            metricas.counter(
                    "orquestrapay.webhooks.provedor",
                    "provedor", nomeCanonico,
                    "resultado", "ignorado").increment();
            return;
        }

        ResultadoAplicacao resultado = switch (notificacao.status()) {
            case "CONFIRMADO" -> confirmar(pagamento, notificacao, agora);
            case "EXPIRADO" -> expirar(pagamento, notificacao, agora);
            case "DEVOLVIDO" -> devolver(pagamento, notificacao, agora);
            default -> throw new IllegalStateException("Status de webhook nao suportado");
        };
        webhooks.concluir(
                nomeCanonico,
                notificacao.idEvento(),
                pagamento.idPagamento(),
                resultado.statusWebhook(),
                resultado.motivo(),
                agora);
        metricas.counter(
                "orquestrapay.webhooks.provedor",
                "provedor", nomeCanonico,
                "resultado", resultado.metrica()).increment();
    }

    private ResultadoAplicacao confirmar(
            RepositorioPagamentos.Pagamento pagamento,
            NotificacaoProvedor notificacao,
            Instant agora) {
        if (pagamento.status() == StatusPagamento.AGUARDANDO_CONFIRMACAO
                && pagamentos.confirmarPix(pagamento.idPagamento(), notificacao.txid(), agora)) {
            registrarEvento(PAGAMENTO_AUTORIZADO, pagamento, true, "PIX confirmado", StatusPagamento.AUTORIZADO);
            return ResultadoAplicacao.processado("Confirmacao PIX aplicada");
        }

        if (pagamento.status() == StatusPagamento.EXPIRADO
                && pagamentos.agendarEstornoPixConfirmadoAposExpiracao(
                        pagamento.idPagamento(),
                        notificacao.txid(),
                        agora)) {
            String detalhes = "O provedor confirmou o PIX depois da expiracao; "
                    + "a compra permanece recusada e a devolucao foi agendada";
            pagamentos.registrarDivergencia(
                    pagamento.idEmpresa(),
                    pagamento.idPagamento(),
                    "PIX_CONFIRMADO_APOS_EXPIRACAO",
                    detalhes,
                    agora);
            operacoes.adicionar(pagamento.idPagamento(), TipoOperacaoPagamento.ESTORNAR, agora);
            metricas.counter("orquestrapay.pix.confirmacoes.tardias").increment();
            return ResultadoAplicacao.processado("Confirmacao tardia recebida; devolucao automatica agendada");
        }

        return ResultadoAplicacao.ignorado(
                "Confirmacao PIX incompativel com o estado " + pagamento.status().name());
    }

    private ResultadoAplicacao expirar(
            RepositorioPagamentos.Pagamento pagamento,
            NotificacaoProvedor notificacao,
            Instant agora) {
        if (pagamento.status() == StatusPagamento.AGUARDANDO_CONFIRMACAO
                && pagamentos.expirarPix(
                        pagamento.idPagamento(),
                        "PIX expirado no provedor",
                        agora)) {
            registrarEvento(PAGAMENTO_RECUSADO, pagamento, false, "PIX expirado", StatusPagamento.EXPIRADO);
            return ResultadoAplicacao.processado("Expiracao PIX aplicada");
        }
        return ResultadoAplicacao.ignorado(
                "Expiracao PIX incompativel com o estado " + pagamento.status().name());
    }

    private ResultadoAplicacao devolver(
            RepositorioPagamentos.Pagamento pagamento,
            NotificacaoProvedor notificacao,
            Instant agora) {
        if (pagamento.status() == StatusPagamento.ESTORNADO) {
            return ResultadoAplicacao.ignorado("Pagamento PIX ja devolvido");
        }
        if (pagamentos.marcarEstornado(pagamento.idPagamento(), notificacao.txid(), agora)) {
            registrarEvento(PAGAMENTO_ESTORNADO, pagamento, true, "PIX devolvido", StatusPagamento.ESTORNADO);
            return ResultadoAplicacao.processado("Devolucao PIX aplicada");
        }
        return ResultadoAplicacao.ignorado(
                "Devolucao PIX incompativel com o estado " + pagamento.status().name());
    }

    private void registrarEvento(
            String tipo,
            RepositorioPagamentos.Pagamento pagamento,
            boolean aprovado,
            String motivo,
            StatusPagamento status) {
        eventos.registrar(
                tipo,
                pagamento.idCompra(),
                pagamento.idEmpresa(),
                pagamento.idCompra(),
                ORIGEM,
                new ResultadoPagamento(
                        pagamento.idPagamento(),
                        pagamento.txid(),
                        aprovado,
                        motivo,
                        status.name(),
                        pagamento.metodoPagamento(),
                        pagamento.provedor(),
                        pagamento.txid(),
                        null,
                        null));
    }

    private NotificacaoProvedor ler(String conteudo) {
        try {
            return json.readValue(conteudo, NotificacaoProvedor.class);
        } catch (JacksonException excecao) {
            throw problema("webhook-invalido", "Conteudo do webhook invalido");
        }
    }

    private void validar(NotificacaoProvedor notificacao) {
        if (!validador.validate(notificacao).isEmpty()) {
            throw new ExcecaoNegocio(
                    HttpStatus.BAD_REQUEST,
                    "webhook-invalido",
                    "O webhook possui campos ausentes ou invalidos");
        }
    }

    private String calcularHash(String conteudo) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(conteudo.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException excecao) {
            throw new IllegalStateException("SHA-256 indisponivel", excecao);
        }
    }

    private void rejeitar(String provedor, String motivo) {
        metricas.counter(
                "orquestrapay.webhooks.provedor",
                "provedor", provedor,
                "resultado", motivo).increment();
    }

    private ExcecaoNegocio problema(String codigo, String detalhe) {
        return new ExcecaoNegocio(HttpStatus.UNAUTHORIZED, codigo, detalhe);
    }

    private record ResultadoAplicacao(
            String statusWebhook,
            String motivo,
            String metrica) {

        private static ResultadoAplicacao processado(String motivo) {
            return new ResultadoAplicacao("PROCESSADO", motivo, "processado");
        }

        private static ResultadoAplicacao ignorado(String motivo) {
            return new ResultadoAplicacao("IGNORADO", motivo, "ignorado");
        }
    }
}
