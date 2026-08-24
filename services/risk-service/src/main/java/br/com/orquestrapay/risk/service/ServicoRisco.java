package br.com.orquestrapay.risk.service;

import static br.com.orquestrapay.contracts.TiposEventos.RISCO_APROVADO;
import static br.com.orquestrapay.contracts.TiposEventos.RISCO_REPROVADO;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import br.com.orquestrapay.contracts.EventoSaga;
import br.com.orquestrapay.contracts.ResultadoRisco;
import br.com.orquestrapay.contracts.SolicitacaoAnaliseRisco;
import br.com.orquestrapay.platform.event.RegistroEventos;
import br.com.orquestrapay.platform.event.RegistroMensagens;
import br.com.orquestrapay.platform.web.ExcecaoNegocio;
import br.com.orquestrapay.risk.api.RespostaAnaliseRisco;
import br.com.orquestrapay.risk.data.RepositorioRisco;
import br.com.orquestrapay.risk.domain.ContextoRisco;
import br.com.orquestrapay.risk.domain.PoliticaRisco;
import br.com.orquestrapay.risk.domain.RegraRisco;
import br.com.orquestrapay.risk.domain.SinalRisco;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ServicoRisco {

    private static final String ORIGEM = "servico-risco";
    private static final String CONSUMIDOR = "risco-v1";
    private final RepositorioRisco repositorio;
    private final RegistroEventos eventos;
    private final RegistroMensagens mensagens;
    private final ObjectMapper json;
    private final Clock relogio;
    private final PoliticaRisco politica;
    private final List<RegraRisco> regras;

    public ServicoRisco(
            RepositorioRisco repositorio,
            RegistroEventos eventos,
            RegistroMensagens mensagens,
            ObjectMapper json,
            Clock relogio,
            PoliticaRisco politica) {
        this.repositorio = repositorio;
        this.eventos = eventos;
        this.mensagens = mensagens;
        this.json = json;
        this.relogio = relogio;
        this.politica = politica;
        this.regras = List.of(
                new RegraRisco.RegraValor(politica),
                new RegraRisco.RegraPais(politica),
                new RegraRisco.RegraVelocidade(politica),
                new RegraRisco.RegraDispositivoCompartilhado(politica));
    }

    @Transactional
    public void analisar(EventoSaga evento) {
        UUID idEvento = UUID.fromString(evento.getIdEvento());
        if (!mensagens.iniciar(idEvento, CONSUMIDOR)) {
            return;
        }

        UUID idEmpresa = UUID.fromString(evento.getIdEmpresa());
        UUID idCompra = UUID.fromString(evento.getIdCompra());
        if (repositorio.existePorCompra(idEmpresa, idCompra)) {
            return;
        }

        SolicitacaoAnaliseRisco solicitacao = ler(evento);
        var agora = relogio.instant();
        var contexto = new ContextoRisco(
                solicitacao.valorTotal(),
                solicitacao.pais(),
                repositorio.contarComprasRecentes(
                        idEmpresa,
                        solicitacao.idCliente(),
                        agora.minus(politica.janelaComprasRecentes())),
                repositorio.contarClientesNoDispositivo(
                        idEmpresa,
                        solicitacao.identificadorDispositivo(),
                        solicitacao.idCliente(),
                        agora.minus(politica.janelaDispositivoCompartilhado())));

        List<SinalRisco> sinais = regras.stream()
                .map(regra -> regra.avaliar(contexto))
                .flatMap(java.util.Optional::stream)
                .toList();
        int pontuacao = Math.min(100, sinais.stream().mapToInt(SinalRisco::pontos).sum());
        boolean aprovada = pontuacao < politica.limiteReprovacao();
        String descricao = sinais.isEmpty()
                ? "Nenhum sinal de risco relevante"
                : sinais.stream()
                        .map(sinal -> sinal.codigo() + ": " + sinal.descricao())
                        .collect(Collectors.joining(" | "));

        repositorio.adicionar(
                idEmpresa,
                idCompra,
                solicitacao.idCliente(),
                solicitacao.identificadorDispositivo(),
                solicitacao.valorTotal(),
                solicitacao.pais(),
                pontuacao,
                aprovada,
                descricao,
                agora);
        eventos.registrar(
                aprovada ? RISCO_APROVADO : RISCO_REPROVADO,
                idCompra,
                idEmpresa,
                idCompra,
                ORIGEM,
                new ResultadoRisco(aprovada, pontuacao, descricao));
    }

    @Transactional(readOnly = true)
    public RespostaAnaliseRisco buscar(UUID idEmpresa, UUID idCompra) {
        return repositorio.buscar(idEmpresa, idCompra)
                .orElseThrow(() -> new ExcecaoNegocio(
                        HttpStatus.NOT_FOUND,
                        "analise-risco-nao-encontrada",
                        "Analise de risco nao encontrada para esta empresa"));
    }

    private SolicitacaoAnaliseRisco ler(EventoSaga evento) {
        try {
            return json.readValue(evento.getConteudo(), SolicitacaoAnaliseRisco.class);
        } catch (JacksonException excecao) {
            throw new IllegalArgumentException("Payload de risco invalido", excecao);
        }
    }
}
