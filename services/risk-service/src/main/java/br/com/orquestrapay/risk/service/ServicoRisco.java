package br.com.orquestrapay.risk.service;

import static br.com.orquestrapay.contracts.TiposEventos.RISCO_APROVADO;
import static br.com.orquestrapay.contracts.TiposEventos.RISCO_REPROVADO;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import br.com.orquestrapay.contracts.EventoSaga;
import br.com.orquestrapay.contracts.ResultadoRisco;
import br.com.orquestrapay.contracts.SolicitacaoAnaliseRisco;
import br.com.orquestrapay.platform.event.RegistroEventos;
import br.com.orquestrapay.platform.event.RegistroMensagens;
import br.com.orquestrapay.platform.web.ExcecaoNegocio;
import br.com.orquestrapay.risk.api.RespostaAnaliseRisco;
import br.com.orquestrapay.risk.api.RespostaComparacaoModelosRisco;
import br.com.orquestrapay.risk.api.RespostaResumoModelosRisco;
import br.com.orquestrapay.risk.data.RepositorioRisco;
import br.com.orquestrapay.risk.domain.ContextoRisco;
import br.com.orquestrapay.risk.domain.ExperimentoModelosRisco;
import br.com.orquestrapay.risk.domain.PoliticaRisco;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationEventPublisher;
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
    private final ExperimentoModelosRisco modelos;
    private final ApplicationEventPublisher publicadorEventosAplicacao;
    private final MetricasModelosRisco metricas;

    public ServicoRisco(
            RepositorioRisco repositorio,
            RegistroEventos eventos,
            RegistroMensagens mensagens,
            ObjectMapper json,
            Clock relogio,
            PoliticaRisco politica,
            ExperimentoModelosRisco modelos,
            ApplicationEventPublisher publicadorEventosAplicacao,
            MetricasModelosRisco metricas) {
        this.repositorio = repositorio;
        this.eventos = eventos;
        this.mensagens = mensagens;
        this.json = json;
        this.relogio = relogio;
        this.politica = politica;
        this.modelos = modelos;
        this.publicadorEventosAplicacao = publicadorEventosAplicacao;
        this.metricas = metricas;
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
        repositorio.bloquearJanelasDeVelocidade(
                idEmpresa,
                solicitacao.idCliente(),
                solicitacao.identificadorDispositivo());
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

        var resultado = modelos.avaliarChampion(contexto);

        repositorio.adicionar(
                idEmpresa,
                idCompra,
                solicitacao.idCliente(),
                solicitacao.identificadorDispositivo(),
                solicitacao.valorTotal(),
                solicitacao.pais(),
                resultado.pontuacao(),
                resultado.aprovada(),
                resultado.descricao(),
                resultado.modelo(),
                resultado.versao(),
                agora);
        eventos.registrar(
                resultado.aprovada() ? RISCO_APROVADO : RISCO_REPROVADO,
                idCompra,
                idEmpresa,
                idCompra,
                ORIGEM,
                new ResultadoRisco(
                        resultado.aprovada(),
                        resultado.pontuacao(),
                        resultado.descricao()));
        metricas.registrarAvaliacao("CHAMPION", resultado);
        if (modelos.deveAvaliarChallenger(idCompra)) {
            publicadorEventosAplicacao.publishEvent(new SolicitacaoAvaliacaoSombra(
                    idEmpresa, idCompra, contexto, resultado));
        }
    }

    @Transactional(readOnly = true)
    public RespostaAnaliseRisco buscar(UUID idEmpresa, UUID idCompra) {
        return repositorio.buscar(idEmpresa, idCompra)
                .orElseThrow(() -> new ExcecaoNegocio(
                        HttpStatus.NOT_FOUND,
                        "analise-risco-nao-encontrada",
                        "Analise de risco nao encontrada para esta empresa"));
    }

    @Transactional(readOnly = true)
    public RespostaComparacaoModelosRisco buscarComparacao(UUID idEmpresa, UUID idCompra) {
        return repositorio.buscarComparacao(idEmpresa, idCompra)
                .orElseThrow(() -> new ExcecaoNegocio(
                        HttpStatus.NOT_FOUND,
                        "comparacao-modelos-nao-encontrada",
                        "Comparacao entre modelos nao encontrada para esta empresa"));
    }

    @Transactional(readOnly = true)
    public RespostaResumoModelosRisco resumirComparacoes(
            UUID idEmpresa,
            Instant desde,
            Instant ate) {
        if (desde == null || ate == null || !desde.isBefore(ate)) {
            throw new ExcecaoNegocio(
                    HttpStatus.BAD_REQUEST,
                    "periodo-comparacao-invalido",
                    "O inicio do periodo deve ser anterior ao fim");
        }
        if (Duration.between(desde, ate).compareTo(Duration.ofDays(90)) > 0) {
            throw new ExcecaoNegocio(
                    HttpStatus.BAD_REQUEST,
                    "periodo-comparacao-muito-amplo",
                    "O periodo de comparacao deve possuir no maximo 90 dias");
        }
        return repositorio.resumirComparacoes(idEmpresa, desde, ate);
    }

    private SolicitacaoAnaliseRisco ler(EventoSaga evento) {
        try {
            return json.readValue(evento.getConteudo(), SolicitacaoAnaliseRisco.class);
        } catch (JacksonException excecao) {
            throw new IllegalArgumentException("Payload de risco invalido", excecao);
        }
    }
}
