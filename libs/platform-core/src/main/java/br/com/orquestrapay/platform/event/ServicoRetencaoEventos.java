package br.com.orquestrapay.platform.event;

import java.time.Clock;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

public class ServicoRetencaoEventos {

    private final RepositorioRetencaoEventos repositorio;
    private final PropriedadesRetencaoEventos propriedades;
    private final Clock relogio;
    private final Counter processadosRemovidos;
    private final Counter publicadosRemovidos;
    private final Counter quarentenaRemovida;

    public ServicoRetencaoEventos(
            RepositorioRetencaoEventos repositorio,
            PropriedadesRetencaoEventos propriedades,
            Clock relogio,
            MeterRegistry metricas) {
        this.repositorio = repositorio;
        this.propriedades = propriedades;
        this.relogio = relogio;
        this.processadosRemovidos = metricas.counter("orquestrapay.retencao.processados.removidos");
        this.publicadosRemovidos = metricas.counter("orquestrapay.retencao.publicados.removidos");
        this.quarentenaRemovida = metricas.counter("orquestrapay.retencao.quarentena.removida");
    }

    @Scheduled(
            fixedDelayString = "${orquestrapay.retencao-eventos.intervalo:1h}",
            initialDelayString = "${orquestrapay.retencao-eventos.atraso-inicial:5m}")
    @Transactional
    public void executar() {
        if (!propriedades.habilitada()) {
            return;
        }

        var agora = relogio.instant();
        int auditorias = repositorio.removerAuditoriasQuarentenaAnterioresA(
                agora.minus(propriedades.quarentena()), propriedades.tamanhoLote());
        int descartados = repositorio.removerDescartadosAnterioresA(
                agora.minus(propriedades.quarentena()), propriedades.tamanhoLote());
        int publicados = repositorio.removerPublicadosAnterioresA(
                agora.minus(propriedades.publicados()), propriedades.tamanhoLote());
        int processados = repositorio.removerProcessadosAnterioresA(
                agora.minus(propriedades.processados()), propriedades.tamanhoLote());

        quarentenaRemovida.increment(auditorias + descartados);
        publicadosRemovidos.increment(publicados);
        processadosRemovidos.increment(processados);
    }
}
