package br.com.orquestrapay.payment.api;

import java.util.UUID;

import br.com.orquestrapay.payment.service.ServicoConciliacao;
import br.com.orquestrapay.payment.service.ServicoPagamento;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PatchMapping;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class ControladorPagamentos {

    private final ServicoPagamento pagamentos;
    private final ServicoConciliacao conciliacao;

    public ControladorPagamentos(ServicoPagamento pagamentos, ServicoConciliacao conciliacao) {
        this.pagamentos = pagamentos;
        this.conciliacao = conciliacao;
    }

    @GetMapping("/pagamentos/compras/{idCompra}")
    @Operation(summary = "Consulta o pagamento de uma compra sem expor o token")
    RespostaPagamento buscar(
            @RequestHeader("X-Empresa-Id") UUID idEmpresa,
            @PathVariable UUID idCompra) {
        return pagamentos.buscar(idEmpresa, idCompra);
    }

    @PostMapping("/conciliacoes")
    @Operation(summary = "Compara o extrato do provedor com os pagamentos locais")
    ResultadoConciliacao conciliar(
            @RequestHeader("X-Empresa-Id") UUID idEmpresa,
            @Valid @RequestBody PedidoConciliacao pedido) {
        return conciliacao.conciliar(idEmpresa, pedido);
    }

    @GetMapping("/conciliacoes")
    @Operation(summary = "Lista as execucoes recentes de conciliacao")
    List<RespostaConciliacaoResumo> listarConciliacoes(
            @RequestHeader("X-Empresa-Id") UUID idEmpresa,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limite) {
        return conciliacao.listar(idEmpresa, limite);
    }

    @GetMapping("/conciliacoes/divergencias")
    @Operation(summary = "Lista divergencias operacionais com filtro de status")
    PaginaDivergencias listarDivergencias(
            @RequestHeader("X-Empresa-Id") UUID idEmpresa,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100_000) int pagina,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int tamanho) {
        return conciliacao.listarDivergencias(idEmpresa, status, pagina, tamanho);
    }

    @PatchMapping("/conciliacoes/divergencias/{idDivergencia}")
    @Operation(summary = "Move uma divergencia pelo fluxo operacional auditado")
    RespostaDivergencia atualizarDivergencia(
            @RequestHeader("X-Empresa-Id") UUID idEmpresa,
            @PathVariable UUID idDivergencia,
            @Valid @RequestBody AtualizacaoDivergencia atualizacao) {
        return conciliacao.atualizarDivergencia(idEmpresa, idDivergencia, atualizacao);
    }
}
