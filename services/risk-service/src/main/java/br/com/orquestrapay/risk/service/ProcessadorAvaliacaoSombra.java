package br.com.orquestrapay.risk.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class ProcessadorAvaliacaoSombra {

    private static final Logger LOG = LoggerFactory.getLogger(ProcessadorAvaliacaoSombra.class);
    private final ServicoComparacaoModelosRisco comparacao;
    private final MetricasModelosRisco metricas;

    public ProcessadorAvaliacaoSombra(
            ServicoComparacaoModelosRisco comparacao,
            MetricasModelosRisco metricas) {
        this.comparacao = comparacao;
        this.metricas = metricas;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void processar(SolicitacaoAvaliacaoSombra solicitacao) {
        try {
            comparacao.comparar(solicitacao);
        } catch (RuntimeException excecao) {
            metricas.registrarFalhaSombra();
            LOG.warn(
                    "A avaliacao challenger falhou sem afetar a decisao champion da compra {}",
                    solicitacao.idCompra(),
                    excecao);
        }
    }
}
