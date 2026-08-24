package br.com.orquestrapay.payment.service;

import static br.com.orquestrapay.contracts.TiposEventos.PAGAMENTO_AUTORIZADO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import br.com.orquestrapay.contracts.EventoSaga;
import br.com.orquestrapay.contracts.ResultadoPagamento;
import br.com.orquestrapay.contracts.SolicitacaoCompensacao;
import br.com.orquestrapay.contracts.SolicitacaoPagamento;
import br.com.orquestrapay.payment.api.RespostaAutorizacaoProvedor;
import br.com.orquestrapay.payment.data.RepositorioPagamentos;
import br.com.orquestrapay.payment.domain.StatusPagamento;
import br.com.orquestrapay.payment.integration.ClienteProvedor;
import br.com.orquestrapay.platform.event.RegistroEventos;
import br.com.orquestrapay.platform.event.RegistroMensagens;
import br.com.orquestrapay.platform.security.ProtecaoTokenPagamento;
import br.com.orquestrapay.platform.web.ExcecaoNegocio;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class TesteServicoPagamento {

    @Mock private RepositorioPagamentos repositorio;
    @Mock private ClienteProvedor provedor;
    @Mock private RegistroEventos eventos;
    @Mock private RegistroMensagens mensagens;
    @Mock private ObjectMapper json;
    @Mock private ProtecaoTokenPagamento protecaoToken;

    @Test
    void deveAutorizarPagamentoSemPersistirOTokenOriginal() throws Exception {
        UUID idEvento = UUID.randomUUID();
        UUID idEmpresa = UUID.randomUUID();
        UUID idCompra = UUID.randomUUID();
        Instant agora = Instant.parse("2026-08-23T12:00:00Z");
        String token = "tok_pagamento_sensivel";
        String tokenProtegido = "v1:token-cifrado";
        var evento = evento(idEvento, idEmpresa, idCompra, "conteudo-pagamento");
        var solicitacao = new SolicitacaoPagamento(
                new BigDecimal("149.90"), "BRL", tokenProtegido);

        when(mensagens.iniciar(idEvento, "pagamento-v1")).thenReturn(true);
        when(repositorio.existePorCompra(idEmpresa, idCompra)).thenReturn(false);
        when(json.readValue("conteudo-pagamento", SolicitacaoPagamento.class)).thenReturn(solicitacao);
        when(protecaoToken.revelar(tokenProtegido, idCompra)).thenReturn(token);
        when(protecaoToken.calcularImpressao("pagamento", token))
                .thenReturn("a".repeat(64));
        when(provedor.autorizar(any())).thenReturn(
                new RespostaAutorizacaoProvedor(true, "aut-42", "Aprovado"));

        var servico = new ServicoPagamento(
                repositorio,
                provedor,
                eventos,
                mensagens,
                json,
                Clock.fixed(agora, ZoneOffset.UTC),
                protecaoToken);
        servico.autorizar(evento);

        verify(provedor).autorizar(argThat(pedido -> pedido.tokenPagamento().equals(token)));

        verify(repositorio).adicionar(
                any(UUID.class),
                eq(idEmpresa),
                eq(idCompra),
                eq(new BigDecimal("149.90")),
                eq("BRL"),
                argThat(impressao -> impressao.length() == 64 && !impressao.contains(token)),
                eq(StatusPagamento.AUTORIZADO),
                eq("aut-42"),
                eq("Aprovado"),
                eq(agora));
        verify(eventos).registrar(
                eq(PAGAMENTO_AUTORIZADO),
                eq(idCompra),
                eq(idEmpresa),
                eq(idCompra),
                eq("servico-pagamento"),
                any(ResultadoPagamento.class));
    }

    @Test
    void deveResponderNaoEncontradoSemVazarDadosDeOutraEmpresa() {
        UUID idEmpresa = UUID.randomUUID();
        UUID idCompra = UUID.randomUUID();
        when(repositorio.buscar(idEmpresa, idCompra)).thenReturn(Optional.empty());
        var servico = new ServicoPagamento(
                repositorio, provedor, eventos, mensagens, json, Clock.systemUTC(), protecaoToken);

        var excecao = catchThrowableOfType(
                ExcecaoNegocio.class,
                () -> servico.buscar(idEmpresa, idCompra));

        assertThat(excecao.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(excecao.codigo()).isEqualTo("pagamento-nao-encontrado");
    }

    @Test
    void deveRecusarEstornoQuandoOEventoPertencerAOutraEmpresa() throws Exception {
        UUID idEvento = UUID.randomUUID();
        UUID idEmpresaEvento = UUID.randomUUID();
        UUID idCompra = UUID.randomUUID();
        UUID idPagamento = UUID.randomUUID();
        var evento = evento(idEvento, idEmpresaEvento, idCompra, "conteudo-estorno");

        when(mensagens.iniciar(idEvento, "pagamento-v1")).thenReturn(true);
        when(json.readValue("conteudo-estorno", SolicitacaoCompensacao.class))
                .thenReturn(new SolicitacaoCompensacao(idPagamento, "falha contabil"));
        when(repositorio.bloquear(idPagamento)).thenReturn(Optional.of(
                new RepositorioPagamentos.Pagamento(
                        idPagamento,
                        UUID.randomUUID(),
                        idCompra,
                        StatusPagamento.AUTORIZADO)));
        var servico = new ServicoPagamento(
                repositorio, provedor, eventos, mensagens, json, Clock.systemUTC(), protecaoToken);

        assertThatThrownBy(() -> servico.estornar(evento))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("O evento de estorno nao pertence ao pagamento informado");
        verifyNoInteractions(provedor, eventos);
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
