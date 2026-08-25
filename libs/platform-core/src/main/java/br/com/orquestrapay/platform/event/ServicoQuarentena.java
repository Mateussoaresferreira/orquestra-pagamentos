package br.com.orquestrapay.platform.event;

import java.time.Clock;
import java.util.Locale;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import br.com.orquestrapay.platform.web.ExcecaoNegocio;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

public class ServicoQuarentena {

    private final RepositorioEventos repositorio;
    private final Clock relogio;

    public ServicoQuarentena(RepositorioEventos repositorio, Clock relogio) {
        this.repositorio = repositorio;
        this.relogio = relogio;
    }

    @Transactional(readOnly = true)
    public PaginaQuarentena listar(
            UUID idEmpresa,
            String status,
            int pagina,
            int tamanho) {
        String statusNormalizado = status.toUpperCase(Locale.ROOT);
        if (!Set.of("ATIVA", "RESOLVIDA", "TODAS").contains(statusNormalizado)) {
            throw new ExcecaoNegocio(
                    HttpStatus.BAD_REQUEST,
                    "status-quarentena-invalido",
                    "Use ATIVA, RESOLVIDA ou TODAS");
        }
        return repositorio.listarQuarentena(
                idEmpresa, statusNormalizado, pagina, tamanho);
    }

    @Transactional(readOnly = true)
    public List<AuditoriaQuarentena> listarAuditoria(
            UUID idEmpresa,
            UUID idEvento) {
        return repositorio.listarAuditoriaQuarentena(idEmpresa, idEvento);
    }

    @Transactional
    public boolean reprocessar(
            UUID idEmpresa,
            UUID idEvento,
            String responsavel,
            String motivo) {
        String motivoValidado = validarMotivo(motivo);
        return repositorio.reprocessarQuarentena(
                idEmpresa,
                idEvento,
                responsavel,
                motivoValidado,
                relogio.instant());
    }

    @Transactional
    public boolean descartarDefinitivamente(
            UUID idEmpresa,
            UUID idEvento,
            String responsavel,
            String motivo) {
        String motivoValidado = validarMotivo(motivo);
        return repositorio.descartarQuarentenaDefinitivamente(
                idEmpresa,
                idEvento,
                responsavel,
                motivoValidado,
                relogio.instant());
    }

    private String validarMotivo(String motivo) {
        String normalizado = motivo == null ? "" : motivo.trim();
        if (normalizado.length() < 10 || normalizado.length() > 500) {
            throw new ExcecaoNegocio(
                    HttpStatus.BAD_REQUEST,
                    "motivo-quarentena-invalido",
                    "Informe um motivo entre 10 e 500 caracteres");
        }
        return normalizado;
    }
}
