package br.com.orquestrapay.platform.event;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.transaction.annotation.Transactional;

public class ServicoFilaEventos {

    private final RepositorioEventos repositorio;
    private final PropriedadesEventos propriedades;
    private final Clock relogio;

    public ServicoFilaEventos(
            RepositorioEventos repositorio,
            PropriedadesEventos propriedades,
            Clock relogio) {
        this.repositorio = repositorio;
        this.propriedades = propriedades;
        this.relogio = relogio;
    }

    @Transactional
    public List<EventoPendente> reivindicar() {
        Instant agora = relogio.instant();
        return repositorio.reivindicarPendentes(
                Math.min(propriedades.tamanhoLote(), propriedades.concorrenciaPublicacao()),
                propriedades.maximoTentativas(),
                agora,
                agora.plus(propriedades.duracaoBloqueio()));
    }

    @Transactional
    public int confirmar(List<EventoPendente> eventos) {
        return repositorio.marcarPublicados(eventos, relogio.instant());
    }

    @Transactional
    public boolean registrarFalha(
            EventoPendente evento,
            String erro,
            Instant proximaTentativaEm,
            Instant descartadoEm) {
        return repositorio.registrarFalha(
                evento.idEvento(),
                evento.tokenBloqueio(),
                erro,
                proximaTentativaEm,
                descartadoEm);
    }
}
