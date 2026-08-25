package br.com.orquestrapay.payment.service;

import static br.com.orquestrapay.contracts.TiposEventos.PAGAMENTO_AUTORIZADO;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import br.com.orquestrapay.contracts.MetodoPagamento;
import br.com.orquestrapay.payment.api.NotificacaoProvedor;
import br.com.orquestrapay.payment.config.PropriedadesPagamentos;
import br.com.orquestrapay.payment.data.RepositorioOperacoesPagamento;
import br.com.orquestrapay.payment.data.RepositorioPagamentos;
import br.com.orquestrapay.payment.data.RepositorioWebhooksProvedor;
import br.com.orquestrapay.payment.data.RepositorioWebhooksProvedor.ResultadoRegistro;
import br.com.orquestrapay.payment.domain.StatusPagamento;
import br.com.orquestrapay.payment.domain.TipoOperacaoPagamento;
import br.com.orquestrapay.payment.integration.CatalogoProvedores;
import br.com.orquestrapay.payment.integration.ClienteProvedor;
import br.com.orquestrapay.platform.event.RegistroEventos;
import br.com.orquestrapay.platform.security.AssinaturaHmac;
import br.com.orquestrapay.platform.web.ExcecaoNegocio;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TesteServicoWebhookProvedor {

    private static final String SEGREDO = "segredo-webhook-principal-com-32-caracteres";
    private static final Instant AGORA = Instant.parse("2026-08-24T15:00:00Z");

    private RepositorioWebhooksProvedor webhooks;
    private RepositorioPagamentos pagamentos;
    private RepositorioOperacoesPagamento operacoes;
    private RegistroEventos eventos;
    private ObjectMapper json;
    private ServicoWebhookProvedor servico;

    @BeforeEach
    void preparar() {
        webhooks = mock(RepositorioWebhooksProvedor.class);
        pagamentos = mock(RepositorioPagamentos.class);
        operacoes = mock(RepositorioOperacoesPagamento.class);
        eventos = mock(RegistroEventos.class);
        json = new ObjectMapper();
        ClienteProvedor principal = mock(ClienteProvedor.class);
        when(principal.nome()).thenReturn("principal");
        when(principal.segredoWebhook()).thenReturn(SEGREDO);
        var propriedades = mock(PropriedadesPagamentos.class);
        when(propriedades.pix()).thenReturn(new PropriedadesPagamentos.Pix(
                Duration.ofMinutes(15),
                URI.create("http://localhost:8083/api/v1/webhooks/provedores"),
                Duration.ofMinutes(5)));
        servico = new ServicoWebhookProvedor(
                new CatalogoProvedores(Map.of("principal", principal)),
                webhooks,
                pagamentos,
                operacoes,
                eventos,
                propriedades,
                json,
                Clock.fixed(AGORA, ZoneOffset.UTC),
                new SimpleMeterRegistry(),
                Validation.buildDefaultValidatorFactory().getValidator());
    }

    @Test
    void deveConfirmarPixAssinadoECanonizarNomeDoProvedor() throws Exception {
        UUID idEvento = UUID.randomUUID();
        UUID idCompra = UUID.randomUUID();
        UUID idPagamento = UUID.randomUUID();
        var notificacao = new NotificacaoProvedor(
                idEvento, idCompra, "pix-123", "CONFIRMADO", AGORA);
        String conteudo = json.writeValueAsString(notificacao);
        long timestamp = AGORA.getEpochSecond();
        String assinatura = AssinaturaHmac.assinar(SEGREDO, timestamp + "." + conteudo);
        when(webhooks.registrar(eq("principal"), eq(idEvento), any(), eq(AGORA)))
                .thenReturn(ResultadoRegistro.NOVO);
        when(pagamentos.bloquearPorPix("principal", "pix-123"))
                .thenReturn(java.util.Optional.of(new RepositorioPagamentos.Pagamento(
                        idPagamento,
                        UUID.randomUUID(),
                        idCompra,
                        StatusPagamento.AGUARDANDO_CONFIRMACAO,
                        MetodoPagamento.PIX,
                        "principal",
                        "pix-123")));
        when(pagamentos.confirmarPix(idPagamento, "pix-123", AGORA)).thenReturn(true);

        servico.processar("PRINCIPAL", timestamp, assinatura, conteudo);

        verify(pagamentos).confirmarPix(idPagamento, "pix-123", AGORA);
        verify(eventos).registrar(
                eq(PAGAMENTO_AUTORIZADO),
                eq(idCompra),
                any(UUID.class),
                eq(idCompra),
                eq("servico-pagamento"),
                any());
        verify(webhooks).concluir(
                "principal", idEvento, idPagamento,
                "PROCESSADO", "Confirmacao PIX aplicada", AGORA);
    }

    @Test
    void deveAgendarDevolucaoQuandoPixForConfirmadoAposExpiracao() throws Exception {
        UUID idEvento = UUID.randomUUID();
        UUID idCompra = UUID.randomUUID();
        UUID idEmpresa = UUID.randomUUID();
        UUID idPagamento = UUID.randomUUID();
        var notificacao = new NotificacaoProvedor(
                idEvento, idCompra, "pix-tardio", "CONFIRMADO", AGORA);
        String conteudo = json.writeValueAsString(notificacao);
        long timestamp = AGORA.getEpochSecond();
        String assinatura = AssinaturaHmac.assinar(SEGREDO, timestamp + "." + conteudo);
        when(webhooks.registrar(eq("principal"), eq(idEvento), any(), eq(AGORA)))
                .thenReturn(ResultadoRegistro.NOVO);
        when(pagamentos.bloquearPorPix("principal", "pix-tardio"))
                .thenReturn(java.util.Optional.of(new RepositorioPagamentos.Pagamento(
                        idPagamento,
                        idEmpresa,
                        idCompra,
                        StatusPagamento.EXPIRADO,
                        MetodoPagamento.PIX,
                        "principal",
                        "pix-tardio")));
        when(pagamentos.agendarEstornoPixConfirmadoAposExpiracao(
                idPagamento, "pix-tardio", AGORA)).thenReturn(true);

        servico.processar("principal", timestamp, assinatura, conteudo);

        verify(pagamentos).registrarDivergencia(
                eq(idEmpresa),
                eq(idPagamento),
                eq("PIX_CONFIRMADO_APOS_EXPIRACAO"),
                any(),
                eq(AGORA));
        verify(operacoes).adicionar(idPagamento, TipoOperacaoPagamento.ESTORNAR, AGORA);
        verifyNoInteractions(eventos);
        verify(webhooks).concluir(
                "principal", idEvento, idPagamento,
                "PROCESSADO",
                "Confirmacao tardia recebida; devolucao automatica agendada",
                AGORA);
    }

    @Test
    void deveAuditarComoIgnoradaUmaConfirmacaoIncompativelComOEstadoAtual() throws Exception {
        UUID idEvento = UUID.randomUUID();
        UUID idCompra = UUID.randomUUID();
        UUID idPagamento = UUID.randomUUID();
        var notificacao = new NotificacaoProvedor(
                idEvento, idCompra, "pix-finalizado", "CONFIRMADO", AGORA);
        String conteudo = json.writeValueAsString(notificacao);
        long timestamp = AGORA.getEpochSecond();
        String assinatura = AssinaturaHmac.assinar(SEGREDO, timestamp + "." + conteudo);
        when(webhooks.registrar(eq("principal"), eq(idEvento), any(), eq(AGORA)))
                .thenReturn(ResultadoRegistro.NOVO);
        when(pagamentos.bloquearPorPix("principal", "pix-finalizado"))
                .thenReturn(java.util.Optional.of(new RepositorioPagamentos.Pagamento(
                        idPagamento,
                        UUID.randomUUID(),
                        idCompra,
                        StatusPagamento.ESTORNADO,
                        MetodoPagamento.PIX,
                        "principal",
                        "pix-finalizado")));

        servico.processar("principal", timestamp, assinatura, conteudo);

        verify(pagamentos, never()).confirmarPix(any(), any(), any());
        verify(pagamentos, never()).agendarEstornoPixConfirmadoAposExpiracao(any(), any(), any());
        verifyNoInteractions(operacoes, eventos);
        verify(webhooks).concluir(
                "principal", idEvento, idPagamento,
                "IGNORADO",
                "Confirmacao PIX incompativel com o estado ESTORNADO",
                AGORA);
    }

    @Test
    void deveSolicitarReenvioQuandoConfirmacaoChegarAntesDoPagamentoLocal() throws Exception {
        UUID idEvento = UUID.randomUUID();
        UUID idCompra = UUID.randomUUID();
        var notificacao = new NotificacaoProvedor(
                idEvento, idCompra, "pix-antecipado", "CONFIRMADO", AGORA);
        String conteudo = json.writeValueAsString(notificacao);
        long timestamp = AGORA.getEpochSecond();
        String assinatura = AssinaturaHmac.assinar(SEGREDO, timestamp + "." + conteudo);
        when(webhooks.registrar(eq("principal"), eq(idEvento), any(), eq(AGORA)))
                .thenReturn(ResultadoRegistro.NOVO);
        when(pagamentos.bloquearPorPix("principal", "pix-antecipado"))
                .thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> servico.processar("principal", timestamp, assinatura, conteudo))
                .isInstanceOf(ExcecaoNegocio.class)
                .extracting("codigo")
                .isEqualTo("pagamento-webhook-indisponivel");

        verify(webhooks, never()).concluir(any(), any(), any(), any(), any(), any());
        verifyNoInteractions(operacoes, eventos);
    }

    @Test
    void deveIgnorarWebhookComCompraDiferenteDoPagamentoLocal() throws Exception {
        UUID idEvento = UUID.randomUUID();
        UUID idCompraInformada = UUID.randomUUID();
        UUID idPagamento = UUID.randomUUID();
        var notificacao = new NotificacaoProvedor(
                idEvento, idCompraInformada, "pix-compra-divergente", "CONFIRMADO", AGORA);
        String conteudo = json.writeValueAsString(notificacao);
        long timestamp = AGORA.getEpochSecond();
        String assinatura = AssinaturaHmac.assinar(SEGREDO, timestamp + "." + conteudo);
        when(webhooks.registrar(eq("principal"), eq(idEvento), any(), eq(AGORA)))
                .thenReturn(ResultadoRegistro.NOVO);
        when(pagamentos.bloquearPorPix("principal", "pix-compra-divergente"))
                .thenReturn(java.util.Optional.of(new RepositorioPagamentos.Pagamento(
                        idPagamento,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        StatusPagamento.AGUARDANDO_CONFIRMACAO,
                        MetodoPagamento.PIX,
                        "principal",
                        "pix-compra-divergente")));

        servico.processar("principal", timestamp, assinatura, conteudo);

        verify(webhooks).concluir(
                "principal", idEvento, null,
                "IGNORADO", "Pagamento PIX nao localizado", AGORA);
        verifyNoInteractions(operacoes, eventos);
    }

    @Test
    void deveRejeitarAssinaturaInvalidaAntesDePersistir() throws Exception {
        var notificacao = new NotificacaoProvedor(
                UUID.randomUUID(), UUID.randomUUID(), "pix-123", "CONFIRMADO", AGORA);
        String conteudo = json.writeValueAsString(notificacao);

        assertThatThrownBy(() -> servico.processar(
                "principal", AGORA.getEpochSecond(), "assinatura-invalida", conteudo))
                .isInstanceOf(ExcecaoNegocio.class)
                .extracting("codigo")
                .isEqualTo("assinatura-invalida");
        verify(webhooks, never()).registrar(any(), any(), any(), any());
    }

    @Test
    void deveIgnorarReenvioDoMesmoEvento() throws Exception {
        var notificacao = new NotificacaoProvedor(
                UUID.randomUUID(), UUID.randomUUID(), "pix-123", "CONFIRMADO", AGORA);
        String conteudo = json.writeValueAsString(notificacao);
        long timestamp = AGORA.getEpochSecond();
        String assinatura = AssinaturaHmac.assinar(SEGREDO, timestamp + "." + conteudo);
        when(webhooks.registrar(eq("principal"), any(), any(), eq(AGORA)))
                .thenReturn(ResultadoRegistro.DUPLICADO);

        servico.processar("principal", timestamp, assinatura, conteudo);

        verify(pagamentos, never()).bloquearPorPix(any(), any());
        verify(eventos, never()).registrar(any(), any(), any(), any(), any(), any());
    }

    @Test
    void deveRejeitarMesmoEventoComConteudoDiferente() throws Exception {
        var notificacao = new NotificacaoProvedor(
                UUID.randomUUID(), UUID.randomUUID(), "pix-conflitante", "CONFIRMADO", AGORA);
        String conteudo = json.writeValueAsString(notificacao);
        long timestamp = AGORA.getEpochSecond();
        String assinatura = AssinaturaHmac.assinar(SEGREDO, timestamp + "." + conteudo);
        when(webhooks.registrar(eq("principal"), any(), any(), eq(AGORA)))
                .thenReturn(ResultadoRegistro.CONFLITANTE);

        assertThatThrownBy(() -> servico.processar("principal", timestamp, assinatura, conteudo))
                .isInstanceOf(ExcecaoNegocio.class)
                .extracting("codigo")
                .isEqualTo("webhook-conflitante");

        verify(pagamentos, never()).bloquearPorPix(any(), any());
        verifyNoInteractions(operacoes, eventos);
    }
}
