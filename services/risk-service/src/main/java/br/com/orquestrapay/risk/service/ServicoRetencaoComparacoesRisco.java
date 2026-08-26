package br.com.orquestrapay.risk.service;

import java.time.Clock;

import br.com.orquestrapay.risk.config.PropriedadesRetencaoComparacoesRisco;
import br.com.orquestrapay.risk.data.RepositorioRisco;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ServicoRetencaoComparacoesRisco {

    private final RepositorioRisco repositorio;
    private final PropriedadesRetencaoComparacoesRisco propriedades;
    private final Clock relogio;
    private final Counter comparacoesRemovidas;

    public ServicoRetencaoComparacoesRisco(
            RepositorioRisco repositorio,
            PropriedadesRetencaoComparacoesRisco propriedades,
            Clock relogio,
            MeterRegistry metricas) {
        this.repositorio = repositorio;
        this.propriedades = propriedades;
        this.relogio = relogio;
        this.comparacoesRemovidas = metricas.counter(
                "orquestrapay.risco.retencao.comparacoes.removidas");
    }

    @Scheduled(
            fixedDelayString = "${orquestrapay.risco.retencao-comparacoes.intervalo:1h}",
            initialDelayString = "${orquestrapay.risco.retencao-comparacoes.atraso-inicial:15m}")
    @Transactional
    public void executar() {
        if (!propriedades.habilitada()) {
            return;
        }

        int removidas = repositorio.removerComparacoesAnterioresA(
                relogio.instant().minus(propriedades.periodo()),
                propriedades.tamanhoLote());
        comparacoesRemovidas.increment(removidas);
    }
}
