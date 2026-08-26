package br.com.orquestrapay.platform.event;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

import br.com.orquestrapay.platform.web.ExcecaoNegocio;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestBody;

@RestController
@RequestMapping("/api/v1/admin/quarentena")
public class ControladorQuarentena {

    private final ServicoQuarentena servico;

    public ControladorQuarentena(ServicoQuarentena servico) {
        this.servico = servico;
    }

    @GetMapping
    @Operation(summary = "Lista eventos em quarentena com paginação e filtro de status")
    public PaginaQuarentena listar(
            @RequestHeader("X-Empresa-Id") UUID idEmpresa,
            @RequestParam(defaultValue = "ATIVA") String status,
            @RequestParam(defaultValue = "0") int pagina,
            @RequestParam(defaultValue = "20") int tamanho) {
        if (pagina < 0 || pagina > 100_000 || tamanho < 1 || tamanho > 100) {
            throw new ExcecaoNegocio(
                    HttpStatus.BAD_REQUEST,
                    "paginacao-invalida",
                    "A pagina deve estar entre 0 e 100000 e o tamanho entre 1 e 100");
        }
        return servico.listar(idEmpresa, status, pagina, tamanho);
    }

    @PostMapping("/{idEvento}/reprocessar")
    @Operation(summary = "Reprocessa um evento em quarentena de forma auditável")
    public ResponseEntity<Void> reprocessar(
            @RequestHeader("X-Empresa-Id") UUID idEmpresa,
            @PathVariable UUID idEvento,
            @RequestBody PedidoTratamentoQuarentena pedido,
            Principal principal) {
        String responsavel = principal == null ? "ambiente-local" : principal.getName();
        if (!servico.reprocessar(idEmpresa, idEvento, responsavel, pedido.motivo())) {
            throw new ExcecaoNegocio(
                    HttpStatus.NOT_FOUND,
                    "evento-quarentena-nao-encontrado",
                    "Evento em quarentena nao encontrado para esta empresa");
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{idEvento}/auditoria")
    @Operation(summary = "Lista o histórico de tratamento de um evento em quarentena")
    public List<AuditoriaQuarentena> listarAuditoria(
            @RequestHeader("X-Empresa-Id") UUID idEmpresa,
            @PathVariable UUID idEvento) {
        return servico.listarAuditoria(idEmpresa, idEvento);
    }

    @PostMapping("/{idEvento}/descartar")
    @Operation(summary = "Descarta definitivamente um evento em quarentena")
    public ResponseEntity<Void> descartarDefinitivamente(
            @RequestHeader("X-Empresa-Id") UUID idEmpresa,
            @PathVariable UUID idEvento,
            @RequestBody PedidoTratamentoQuarentena pedido,
            Principal principal) {
        String responsavel = principal == null ? "ambiente-local" : principal.getName();
        if (!servico.descartarDefinitivamente(
                idEmpresa, idEvento, responsavel, pedido.motivo())) {
            throw new ExcecaoNegocio(
                    HttpStatus.NOT_FOUND,
                    "evento-quarentena-nao-encontrado",
                    "Evento ativo em quarentena nao encontrado para esta empresa");
        }
        return ResponseEntity.noContent().build();
    }
}
