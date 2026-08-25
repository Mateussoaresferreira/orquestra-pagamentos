package br.com.orquestrapay.platform.event;

import java.security.Principal;
import java.util.UUID;

import br.com.orquestrapay.platform.web.ExcecaoNegocio;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/quarentena")
public class ControladorQuarentena {

    private final ServicoQuarentena servico;

    public ControladorQuarentena(ServicoQuarentena servico) {
        this.servico = servico;
    }

    @GetMapping
    public PaginaQuarentena listar(
            @RequestHeader("X-Empresa-Id") UUID idEmpresa,
            @RequestParam(defaultValue = "0") int pagina,
            @RequestParam(defaultValue = "20") int tamanho) {
        if (pagina < 0 || tamanho < 1 || tamanho > 100) {
            throw new ExcecaoNegocio(
                    HttpStatus.BAD_REQUEST,
                    "paginacao-invalida",
                    "A pagina deve ser positiva e o tamanho deve estar entre 1 e 100");
        }
        return servico.listar(idEmpresa, pagina, tamanho);
    }

    @PostMapping("/{idEvento}/reprocessar")
    public ResponseEntity<Void> reprocessar(
            @RequestHeader("X-Empresa-Id") UUID idEmpresa,
            @PathVariable UUID idEvento,
            Principal principal) {
        String responsavel = principal == null ? "ambiente-local" : principal.getName();
        if (!servico.reprocessar(idEmpresa, idEvento, responsavel)) {
            throw new ExcecaoNegocio(
                    HttpStatus.NOT_FOUND,
                    "evento-quarentena-nao-encontrado",
                    "Evento em quarentena nao encontrado para esta empresa");
        }
        return ResponseEntity.noContent().build();
    }
}
