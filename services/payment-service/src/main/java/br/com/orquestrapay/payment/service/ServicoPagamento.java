package br.com.orquestrapay.payment.service;

import static br.com.orquestrapay.contracts.TiposEventos.PAGAMENTO_ESTORNADO;

import java.time.Clock;
import java.util.UUID;

import br.com.orquestrapay.contracts.EventoSaga;
import br.com.orquestrapay.contracts.MetodoPagamento;
import br.com.orquestrapay.contracts.ResultadoPagamento;
import br.com.orquestrapay.contracts.SolicitacaoCompensacao;
import br.com.orquestrapay.contracts.SolicitacaoPagamento;
import br.com.orquestrapay.payment.api.RespostaPagamento;
import br.com.orquestrapay.payment.data.RepositorioOperacoesPagamento;
import br.com.orquestrapay.payment.data.RepositorioPagamentos;
import br.com.orquestrapay.payment.domain.StatusPagamento;
import br.com.orquestrapay.payment.domain.TipoOperacaoPagamento;
import br.com.orquestrapay.platform.event.RegistroEventos;
import br.com.orquestrapay.platform.event.RegistroMensagens;
import br.com.orquestrapay.platform.web.ExcecaoNegocio;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ServicoPagamento {

    private static final String ORIGEM = "servico-pagamento";
    private static final String CONSUMIDOR = "pagamento-v1";

    private final RepositorioPagamentos repositorio;
    private final RepositorioOperacoesPagamento operacoes;
    private final RegistroEventos eventos;
    private final RegistroMensagens mensagens;
    private final ObjectMapper json;
    private final Clock relogio;

    public ServicoPagamento(
            RepositorioPagamentos repositorio,
            RepositorioOperacoesPagamento operacoes,
            RegistroEventos eventos,
            RegistroMensagens mensagens,
            ObjectMapper json,
            Clock relogio) {
        this.repositorio = repositorio;
        this.operacoes = operacoes;
        this.eventos = eventos;
        this.mensagens = mensagens;
        this.json = json;
        this.relogio = relogio;
    }

    @Transactional
    public void autorizar(EventoSaga evento) {
        UUID idEvento = UUID.fromString(evento.getIdEvento());
        if (!mensagens.iniciar(idEvento, CONSUMIDOR)) {
            return;
        }

        UUID idEmpresa = UUID.fromString(evento.getIdEmpresa());
        UUID idCompra = UUID.fromString(evento.getIdCompra());
        SolicitacaoPagamento solicitacao = ler(evento, SolicitacaoPagamento.class);
        validar(solicitacao);

        var agora = relogio.instant();
        UUID idPagamento = repositorio.adicionarPendente(
                idEmpresa,
                idCompra,
                solicitacao.valorTotal(),
                solicitacao.moeda(),
                solicitacao.tokenPagamento(),
                solicitacao.metodoPagamento(),
                solicitacao.parcelas(),
                agora);
        TipoOperacaoPagamento tipo = solicitacao.metodoPagamento() == MetodoPagamento.PIX
                ? TipoOperacaoPagamento.CRIAR_PIX
                : TipoOperacaoPagamento.AUTORIZAR_CARTAO;
        operacoes.adicionar(idPagamento, tipo, agora);
    }

    @Transactional
    public void estornar(EventoSaga evento) {
        UUID idEvento = UUID.fromString(evento.getIdEvento());
        if (!mensagens.iniciar(idEvento, CONSUMIDOR)) {
            return;
        }

        UUID idEmpresaEvento = UUID.fromString(evento.getIdEmpresa());
        UUID idCompraEvento = UUID.fromString(evento.getIdCompra());
        SolicitacaoCompensacao solicitacao = ler(evento, SolicitacaoCompensacao.class);
        var pagamento = repositorio.bloquear(solicitacao.idReferencia())
                .orElseThrow(() -> new IllegalStateException(
                        "Pagamento nao encontrado: " + solicitacao.idReferencia()));
        if (!pagamento.idEmpresa().equals(idEmpresaEvento)
                || !pagamento.idCompra().equals(idCompraEvento)) {
            throw new IllegalStateException(
                    "O evento de estorno nao pertence ao pagamento informado");
        }

        if (pagamento.status() == StatusPagamento.ESTORNADO) {
            registrarEstornoConcluido(pagamento, "ja-estornado");
            return;
        }
        if (pagamento.status() != StatusPagamento.AUTORIZADO
                && pagamento.status() != StatusPagamento.ESTORNO_PENDENTE
                && pagamento.status() != StatusPagamento.ESTORNANDO
                && pagamento.status() != StatusPagamento.FALHA_TECNICA) {
            throw new IllegalStateException(
                    "Somente um pagamento autorizado pode ser estornado");
        }

        var agora = relogio.instant();
        if (pagamento.status() == StatusPagamento.AUTORIZADO) {
            repositorio.marcarEstornoPendente(pagamento.idPagamento(), agora);
        }
        operacoes.adicionar(pagamento.idPagamento(), TipoOperacaoPagamento.ESTORNAR, agora);
    }

    @Transactional(readOnly = true)
    public RespostaPagamento buscar(UUID idEmpresa, UUID idCompra) {
        return repositorio.buscar(idEmpresa, idCompra)
                .orElseThrow(() -> new ExcecaoNegocio(
                        HttpStatus.NOT_FOUND,
                        "pagamento-nao-encontrado",
                        "Pagamento nao encontrado para esta empresa"));
    }

    private void registrarEstornoConcluido(
            RepositorioPagamentos.Pagamento pagamento,
            String protocolo) {
        eventos.registrar(
                PAGAMENTO_ESTORNADO,
                pagamento.idCompra(),
                pagamento.idEmpresa(),
                pagamento.idCompra(),
                ORIGEM,
                new ResultadoPagamento(
                        pagamento.idPagamento(),
                        protocolo,
                        true,
                        "Pagamento estornado de forma idempotente",
                        StatusPagamento.ESTORNADO.name(),
                        pagamento.metodoPagamento(),
                        pagamento.provedor(),
                        pagamento.txid(),
                        null,
                        null));
    }

    private void validar(SolicitacaoPagamento solicitacao) {
        if (solicitacao.valorTotal() == null || solicitacao.valorTotal().signum() <= 0) {
            throw new IllegalArgumentException("O valor do pagamento deve ser positivo");
        }
        if (solicitacao.moeda() == null || !solicitacao.moeda().matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("A moeda do pagamento e invalida");
        }
        if (solicitacao.parcelas() < 1 || solicitacao.parcelas() > 12) {
            throw new IllegalArgumentException("A quantidade de parcelas deve ficar entre 1 e 12");
        }
        if (solicitacao.metodoPagamento() == MetodoPagamento.PIX && solicitacao.parcelas() != 1) {
            throw new IllegalArgumentException("PIX nao permite parcelamento");
        }
        if (solicitacao.metodoPagamento() == MetodoPagamento.CARTAO
                && (solicitacao.tokenPagamento() == null || solicitacao.tokenPagamento().isBlank())) {
            throw new IllegalArgumentException("O token protegido do cartao e obrigatorio");
        }
    }

    private <T> T ler(EventoSaga evento, Class<T> tipo) {
        try {
            return json.readValue(evento.getConteudo(), tipo);
        } catch (JacksonException excecao) {
            throw new IllegalArgumentException("Payload de pagamento invalido", excecao);
        }
    }
}
