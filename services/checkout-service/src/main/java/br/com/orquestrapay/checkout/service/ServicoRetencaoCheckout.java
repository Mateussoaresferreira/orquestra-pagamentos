package br.com.orquestrapay.checkout.service;

import java.time.Clock;

import br.com.orquestrapay.checkout.config.PropriedadesRetencaoCheckout;
import br.com.orquestrapay.checkout.data.RepositorioCompras;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ServicoRetencaoCheckout {

    private final RepositorioCompras repositorio;
    private final PropriedadesRetencaoCheckout propriedades;
    private final Clock relogio;
    private final Counter chavesRemovidas;

    public ServicoRetencaoCheckout(
            RepositorioCompras repositorio,
            PropriedadesRetencaoCheckout propriedades,
            Clock relogio,
            MeterRegistry metricas) {
        this.repositorio = repositorio;
        this.propriedades = propriedades;
        this.relogio = relogio;
        this.chavesRemovidas = metricas.counter("orquestrapay.retencao.idempotencia.removida");
    }

    @Scheduled(
            fixedDelayString = "${orquestrapay.retencao-checkout.intervalo:1h}",
            initialDelayString = "${orquestrapay.retencao-checkout.atraso-inicial:10m}")
    @Transactional
    public void executar() {
        if (!propriedades.habilitada()) {
            return;
        }

        int removidas = repositorio.removerIdempotenciasAnterioresA(
                relogio.instant().minus(propriedades.chavesIdempotencia()),
                propriedades.tamanhoLote());
        chavesRemovidas.increment(removidas);
    }
}
