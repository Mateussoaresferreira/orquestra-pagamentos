package br.com.orquestrapay.platform.event;

import java.time.Clock;
import java.util.UUID;

import org.springframework.transaction.annotation.Transactional;

public class ServicoQuarentena {

    private final RepositorioEventos repositorio;
    private final Clock relogio;

    public ServicoQuarentena(RepositorioEventos repositorio, Clock relogio) {
        this.repositorio = repositorio;
        this.relogio = relogio;
    }

    @Transactional(readOnly = true)
    public PaginaQuarentena listar(UUID idEmpresa, int pagina, int tamanho) {
        return repositorio.listarQuarentena(idEmpresa, pagina, tamanho);
    }

    @Transactional
    public boolean reprocessar(UUID idEmpresa, UUID idEvento, String responsavel) {
        return repositorio.reprocessarQuarentena(
                idEmpresa,
                idEvento,
                responsavel,
                relogio.instant());
    }
}
