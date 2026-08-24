package br.com.orquestrapay.payment.service;

import static br.com.orquestrapay.contracts.TiposEventos.PAGAMENTO_AUTORIZADO;
import static br.com.orquestrapay.contracts.TiposEventos.PAGAMENTO_ESTORNADO;
import static br.com.orquestrapay.contracts.TiposEventos.PAGAMENTO_RECUSADO;

import java.time.Clock;
import java.util.UUID;

import br.com.orquestrapay.contracts.EventoSaga;
import br.com.orquestrapay.contracts.ResultadoPagamento;
import br.com.orquestrapay.contracts.SolicitacaoCompensacao;
import br.com.orquestrapay.contracts.SolicitacaoPagamento;
import br.com.orquestrapay.payment.api.PedidoAutorizacaoProvedor;
import br.com.orquestrapay.payment.api.PedidoEstornoProvedor;
import br.com.orquestrapay.payment.api.RespostaPagamento;
import br.com.orquestrapay.payment.data.RepositorioPagamentos;
import br.com.orquestrapay.payment.domain.StatusPagamento;
import br.com.orquestrapay.payment.integration.ClienteProvedor;
import br.com.orquestrapay.platform.event.RegistroEventos;
import br.com.orquestrapay.platform.event.RegistroMensagens;
import br.com.orquestrapay.platform.security.ProtecaoTokenPagamento;
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
    private final ClienteProvedor provedor;
    private final RegistroEventos eventos;
    private final RegistroMensagens mensagens;
    private final ObjectMapper json;
    private final Clock relogio;
    private final ProtecaoTokenPagamento protecaoToken;

    public ServicoPagamento(
            RepositorioPagamentos repositorio,
            ClienteProvedor provedor,
            RegistroEventos eventos,
            RegistroMensagens mensagens,
            ObjectMapper json,
            Clock relogio,
            ProtecaoTokenPagamento protecaoToken) {
        this.repositorio = repositorio;
        this.provedor = provedor;
        this.eventos = eventos;
        this.mensagens = mensagens;
        this.json = json;
        this.relogio = relogio;
        this.protecaoToken = protecaoToken;
    }

    @Transactional
    public void autorizar(EventoSaga evento) {
        UUID idEvento = UUID.fromString(evento.getIdEvento());
        if (!mensagens.iniciar(idEvento, CONSUMIDOR)) {
            return;
        }

        UUID idEmpresa = UUID.fromString(evento.getIdEmpresa());
        UUID idCompra = UUID.fromString(evento.getIdCompra());
        if (repositorio.existePorCompra(idEmpresa, idCompra)) {
            return;
        }

        SolicitacaoPagamento solicitacao = ler(evento, SolicitacaoPagamento.class);
        String tokenPagamento = protecaoToken.revelar(solicitacao.tokenPagamento(), idCompra);
        var resposta = provedor.autorizar(new PedidoAutorizacaoProvedor(
                idCompra,
                solicitacao.valorTotal(),
                solicitacao.moeda(),
                tokenPagamento));
        if (resposta == null) {
            throw new IllegalStateException("O provedor retornou resposta vazia");
        }

        UUID idPagamento = UUID.randomUUID();
        StatusPagamento status = resposta.aprovada()
                ? StatusPagamento.AUTORIZADO
                : StatusPagamento.RECUSADO;
        repositorio.adicionar(
                idPagamento,
                idEmpresa,
                idCompra,
                solicitacao.valorTotal(),
                solicitacao.moeda(),
                protecaoToken.calcularImpressao("pagamento", tokenPagamento),
                status,
                resposta.idAutorizacao(),
                resposta.motivo(),
                relogio.instant());
        eventos.registrar(
                resposta.aprovada() ? PAGAMENTO_AUTORIZADO : PAGAMENTO_RECUSADO,
                idCompra,
                idEmpresa,
                idCompra,
                ORIGEM,
                new ResultadoPagamento(
                        idPagamento,
                        resposta.idAutorizacao(),
                        resposta.aprovada(),
                        resposta.motivo()));
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
        String protocolo = "ja-estornado";
        if (pagamento.status() == StatusPagamento.AUTORIZADO) {
            var resposta = provedor.estornar(new PedidoEstornoProvedor(pagamento.idPagamento()));
            if (resposta == null || !resposta.estornado()) {
                throw new IllegalStateException("O provedor nao confirmou o estorno");
            }
            protocolo = resposta.protocolo();
            repositorio.marcarEstornado(pagamento.idPagamento(), protocolo, relogio.instant());
        }

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
                        "Pagamento estornado de forma idempotente"));
    }

    @Transactional(readOnly = true)
    public RespostaPagamento buscar(UUID idEmpresa, UUID idCompra) {
        return repositorio.buscar(idEmpresa, idCompra)
                .orElseThrow(() -> new ExcecaoNegocio(
                        HttpStatus.NOT_FOUND,
                        "pagamento-nao-encontrado",
                        "Pagamento nao encontrado para esta empresa"));
    }

    private <T> T ler(EventoSaga evento, Class<T> tipo) {
        try {
            return json.readValue(evento.getConteudo(), tipo);
        } catch (JacksonException excecao) {
            throw new IllegalArgumentException("Payload de pagamento invalido", excecao);
        }
    }
}
