package br.com.orquestrapay.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
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
import br.com.orquestrapay.contracts.MetodoPagamento;
import br.com.orquestrapay.contracts.SolicitacaoCompensacao;
import br.com.orquestrapay.contracts.SolicitacaoPagamento;
import br.com.orquestrapay.payment.data.RepositorioOperacoesPagamento;
import br.com.orquestrapay.payment.data.RepositorioPagamentos;
import br.com.orquestrapay.payment.domain.StatusPagamento;
import br.com.orquestrapay.payment.domain.TipoOperacaoPagamento;
import br.com.orquestrapay.platform.event.RegistroEventos;
import br.com.orquestrapay.platform.event.RegistroMensagens;
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
    @Mock private RepositorioOperacoesPagamento operacoes;
    @Mock private RegistroEventos eventos;
    @Mock private RegistroMensagens mensagens;
    @Mock private ObjectMapper json;

    @Test
    void devePersistirAIntencaoSemRevelarOTokenNemChamarOProvedor() throws Exception {
        UUID idEvento = UUID.randomUUID();
        UUID idEmpresa = UUID.randomUUID();
        UUID idCompra = UUID.randomUUID();
        UUID idPagamento = UUID.randomUUID();
        Instant agora = Instant.parse("2026-08-23T12:00:00Z");
        String tokenProtegido = "v2:ativa:token-cifrado";
        var evento = evento(idEvento, idEmpresa, idCompra, "conteudo-pagamento");
        var solicitacao = new SolicitacaoPagamento(
                new BigDecimal("149.90"),
                "BRL",
                tokenProtegido,
                MetodoPagamento.CARTAO,
                3);

        when(mensagens.iniciar(idEvento, "pagamento-v1")).thenReturn(true);
        when(json.readValue("conteudo-pagamento", SolicitacaoPagamento.class)).thenReturn(solicitacao);
        when(repositorio.adicionarPendente(
                idEmpresa,
                idCompra,
                solicitacao.valorTotal(),
                "BRL",
                tokenProtegido,
                MetodoPagamento.CARTAO,
                3,
                agora)).thenReturn(idPagamento);

        servico(agora).autorizar(evento);

        verify(operacoes).adicionar(
                idPagamento,
                TipoOperacaoPagamento.AUTORIZAR_CARTAO,
                agora);
        verifyNoInteractions(eventos);
    }

    @Test
    void deveEnfileirarPixSemExigirTokenDeCartao() throws Exception {
        UUID idEvento = UUID.randomUUID();
        UUID idEmpresa = UUID.randomUUID();
        UUID idCompra = UUID.randomUUID();
        UUID idPagamento = UUID.randomUUID();
        Instant agora = Instant.parse("2026-08-23T12:00:00Z");
        var evento = evento(idEvento, idEmpresa, idCompra, "conteudo-pix");
        var solicitacao = new SolicitacaoPagamento(
                new BigDecimal("49.90"), "BRL", null, MetodoPagamento.PIX, 1);

        when(mensagens.iniciar(idEvento, "pagamento-v1")).thenReturn(true);
        when(json.readValue("conteudo-pix", SolicitacaoPagamento.class)).thenReturn(solicitacao);
        when(repositorio.adicionarPendente(
                idEmpresa,
                idCompra,
                solicitacao.valorTotal(),
                "BRL",
                null,
                MetodoPagamento.PIX,
                1,
                agora)).thenReturn(idPagamento);

        servico(agora).autorizar(evento);

        verify(operacoes).adicionar(idPagamento, TipoOperacaoPagamento.CRIAR_PIX, agora);
    }

    @Test
    void deveResponderNaoEncontradoSemVazarDadosDeOutraEmpresa() {
        UUID idEmpresa = UUID.randomUUID();
        UUID idCompra = UUID.randomUUID();
        when(repositorio.buscar(idEmpresa, idCompra)).thenReturn(Optional.empty());

        var excecao = catchThrowableOfType(
                ExcecaoNegocio.class,
                () -> servico(Instant.now()).buscar(idEmpresa, idCompra));

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

        assertThatThrownBy(() -> servico(Instant.now()).estornar(evento))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("O evento de estorno nao pertence ao pagamento informado");
        verifyNoInteractions(operacoes, eventos);
    }

    private ServicoPagamento servico(Instant agora) {
        return new ServicoPagamento(
                repositorio,
                operacoes,
                eventos,
                mensagens,
                json,
                Clock.fixed(agora, ZoneOffset.UTC));
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
