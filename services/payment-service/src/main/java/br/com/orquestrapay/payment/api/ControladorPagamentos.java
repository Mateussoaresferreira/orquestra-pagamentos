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
}
