package br.com.orquestrapay.ledger.service;

import static br.com.orquestrapay.contracts.TiposEventos.LANCAMENTOS_REGISTRADOS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import br.com.orquestrapay.contracts.EventoSaga;
import br.com.orquestrapay.contracts.ResultadoLancamentos;
import br.com.orquestrapay.contracts.SolicitacaoLancamentos;
import br.com.orquestrapay.ledger.data.RepositorioRazao;
import br.com.orquestrapay.ledger.domain.NaturezaLancamento;
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
class TesteServicoRazao {

    @Mock private RepositorioRazao repositorio;
    @Mock private RegistroEventos eventos;
    @Mock private RegistroMensagens mensagens;
    @Mock private ObjectMapper json;

    @Test
    void deveRegistrarDebitoECreditoComMesmoValor() throws Exception {
        UUID idEvento = UUID.randomUUID();
        UUID idEmpresa = UUID.randomUUID();
        UUID idCompra = UUID.randomUUID();
        UUID idPagamento = UUID.randomUUID();
        Instant agora = Instant.parse("2026-08-23T12:00:00Z");
        var evento = evento(idEvento, idEmpresa, idCompra, "conteudo-contabil");
        var solicitacao = new SolicitacaoLancamentos(
                idPagamento, new BigDecimal("149.90"), "BRL");

        when(mensagens.iniciar(idEvento, "razao-v1")).thenReturn(true);
        when(repositorio.existePorCompra(idEmpresa, idCompra)).thenReturn(false);
        when(json.readValue("conteudo-contabil", SolicitacaoLancamentos.class)).thenReturn(solicitacao);

        var servico = new ServicoRazao(
                repositorio,
                eventos,
                mensagens,
                json,
                Clock.fixed(agora, ZoneOffset.UTC));
        servico.registrar(evento);

        verify(repositorio).abrir(
                any(UUID.class),
                eq(idEmpresa),
                eq(idCompra),
                eq(idPagamento),
                eq(new BigDecimal("149.90")),
                eq("BRL"),
                eq(agora));
        verify(repositorio, times(2)).lancar(
                any(UUID.class),
                any(String.class),
                any(NaturezaLancamento.class),
                eq(new BigDecimal("149.90")),
                eq("BRL"),
                eq(agora));
        verify(repositorio).fechar(any(UUID.class));
        verify(eventos).registrar(
                eq(LANCAMENTOS_REGISTRADOS),
                eq(idCompra),
                eq(idEmpresa),
                eq(idCompra),
                eq("servico-razao"),
                any(ResultadoLancamentos.class));
    }

    @Test
    void deveResponderNaoEncontradoParaEmpresaSemTransacao() {
        UUID idEmpresa = UUID.randomUUID();
        UUID idCompra = UUID.randomUUID();
        when(repositorio.buscar(idEmpresa, idCompra)).thenReturn(Optional.empty());
        var servico = new ServicoRazao(
                repositorio, eventos, mensagens, json, Clock.systemUTC());

        var excecao = catchThrowableOfType(
                ExcecaoNegocio.class,
                () -> servico.buscar(idEmpresa, idCompra));

        assertThat(excecao.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(excecao.codigo()).isEqualTo("transacao-contabil-nao-encontrada");
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
