package br.com.orquestrapay.risk.service;

import java.time.Clock;

import br.com.orquestrapay.risk.data.RepositorioRisco;
import br.com.orquestrapay.risk.domain.ClassificacaoComparacaoRisco;
import br.com.orquestrapay.risk.domain.ExperimentoModelosRisco;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ServicoComparacaoModelosRisco {

    private final RepositorioRisco repositorio;
    private final ExperimentoModelosRisco modelos;
    private final MetricasModelosRisco metricas;
    private final Clock relogio;

    public ServicoComparacaoModelosRisco(
            RepositorioRisco repositorio,
            ExperimentoModelosRisco modelos,
            MetricasModelosRisco metricas,
            Clock relogio) {
        this.repositorio = repositorio;
        this.modelos = modelos;
        this.metricas = metricas;
        this.relogio = relogio;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void comparar(SolicitacaoAvaliacaoSombra solicitacao) {
        modelos.avaliarChallenger(solicitacao.contexto()).ifPresent(challenger -> {
            var classificacao = ClassificacaoComparacaoRisco.comparar(
                    solicitacao.champion(), challenger);
            boolean adicionada = repositorio.adicionarComparacao(
                    solicitacao.idEmpresa(),
                    solicitacao.idCompra(),
                    solicitacao.champion(),
                    challenger,
                    classificacao,
                    relogio.instant());
            if (adicionada) {
                metricas.registrarAvaliacao("CHALLENGER", challenger);
                metricas.registrarComparacao(
                        classificacao,
                        challenger.pontuacao() - solicitacao.champion().pontuacao());
            }
        });
    }
}
