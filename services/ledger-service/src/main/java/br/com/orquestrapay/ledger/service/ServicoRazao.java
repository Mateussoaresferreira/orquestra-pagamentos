package br.com.orquestrapay.ledger.service;

import static br.com.orquestrapay.contracts.TiposEventos.LANCAMENTOS_RECUSADOS;
import static br.com.orquestrapay.contracts.TiposEventos.LANCAMENTOS_REGISTRADOS;

import java.time.Clock;
import java.util.UUID;

import br.com.orquestrapay.contracts.EventoSaga;
import br.com.orquestrapay.contracts.ResultadoLancamentos;
import br.com.orquestrapay.contracts.SolicitacaoLancamentos;
import br.com.orquestrapay.ledger.api.RespostaTransacaoContabil;
import br.com.orquestrapay.ledger.data.RepositorioRazao;
import br.com.orquestrapay.ledger.domain.NaturezaLancamento;
import br.com.orquestrapay.platform.event.RegistroEventos;
import br.com.orquestrapay.platform.event.RegistroMensagens;
import br.com.orquestrapay.platform.web.ExcecaoNegocio;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ServicoRazao {

    private static final String ORIGEM = "servico-razao";
    private static final String CONSUMIDOR = "razao-v1";

    private final RepositorioRazao repositorio;
    private final RegistroEventos eventos;
    private final RegistroMensagens mensagens;
    private final ObjectMapper json;
    private final Clock relogio;

    public ServicoRazao(
            RepositorioRazao repositorio,
            RegistroEventos eventos,
            RegistroMensagens mensagens,
            ObjectMapper json,
            Clock relogio) {
        this.repositorio = repositorio;
        this.eventos = eventos;
        this.mensagens = mensagens;
        this.json = json;
        this.relogio = relogio;
    }

    @Transactional
    public void registrar(EventoSaga evento) {
        UUID idEvento = UUID.fromString(evento.getIdEvento());
        if (!mensagens.iniciar(idEvento, CONSUMIDOR)) {
            return;
        }

        UUID idEmpresa = UUID.fromString(evento.getIdEmpresa());
        UUID idCompra = UUID.fromString(evento.getIdCompra());
        if (repositorio.existePorCompra(idEmpresa, idCompra)) {
            return;
        }

        SolicitacaoLancamentos solicitacao = ler(evento);
        UUID idTransacao = UUID.randomUUID();
        if ("XXX".equals(solicitacao.moeda())) {
            String motivo = "Moeda de teste XXX recusada pelo razao contabil";
            repositorio.rejeitar(
                    idTransacao, idEmpresa, idCompra, solicitacao.idPagamento(),
                    solicitacao.valorTotal(), solicitacao.moeda(), motivo, relogio.instant());
            eventos.registrar(
                    LANCAMENTOS_RECUSADOS, idCompra, idEmpresa, idCompra, ORIGEM,
                    new ResultadoLancamentos(idTransacao, false, motivo));
            return;
        }

        var agora = relogio.instant();
        repositorio.abrir(
                idTransacao, idEmpresa, idCompra, solicitacao.idPagamento(),
                solicitacao.valorTotal(), solicitacao.moeda(), agora);
        repositorio.lancar(
                idTransacao,
                "VALORES_A_RECEBER_DO_PROVEDOR",
                NaturezaLancamento.DEBITO,
                solicitacao.valorTotal(),
                solicitacao.moeda(),
                agora);
        repositorio.lancar(
                idTransacao,
                "RECEITA_DE_VENDAS",
                NaturezaLancamento.CREDITO,
                solicitacao.valorTotal(),
                solicitacao.moeda(),
                agora);
        repositorio.fechar(idTransacao);
        eventos.registrar(
                LANCAMENTOS_REGISTRADOS, idCompra, idEmpresa, idCompra, ORIGEM,
                new ResultadoLancamentos(idTransacao, true, "Partidas dobradas balanceadas"));
    }

    @Transactional(readOnly = true)
    public RespostaTransacaoContabil buscar(UUID idEmpresa, UUID idCompra) {
        return repositorio.buscar(idEmpresa, idCompra)
                .orElseThrow(() -> new ExcecaoNegocio(
                        HttpStatus.NOT_FOUND,
                        "transacao-contabil-nao-encontrada",
                        "Transacao contabil nao encontrada para esta empresa"));
    }

    private SolicitacaoLancamentos ler(EventoSaga evento) {
        try {
            return json.readValue(evento.getConteudo(), SolicitacaoLancamentos.class);
        } catch (JacksonException excecao) {
            throw new IllegalArgumentException("Payload contabil invalido", excecao);
        }
    }
}
