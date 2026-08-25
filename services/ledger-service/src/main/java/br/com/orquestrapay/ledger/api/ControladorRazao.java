package br.com.orquestrapay.ledger.api;

import java.util.UUID;

import br.com.orquestrapay.ledger.service.ServicoRazao;
import br.com.orquestrapay.ledger.service.ServicoParcelas;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.validation.annotation.Validated;

@Validated
@RestController
@RequestMapping("/api/v1/transacoes-contabeis")
public class ControladorRazao {

    private final ServicoRazao razao;
    private final ServicoParcelas parcelas;

    public ControladorRazao(ServicoRazao razao, ServicoParcelas parcelas) {
        this.razao = razao;
        this.parcelas = parcelas;
    }

    @GetMapping("/compras/{idCompra}")
    @Operation(summary = "Consulta a transacao e suas partidas imutaveis")
    RespostaTransacaoContabil buscar(
            @RequestHeader("X-Empresa-Id") UUID idEmpresa,
            @PathVariable UUID idCompra) {
        return razao.buscar(idEmpresa, idCompra);
    }

    @PatchMapping("/compras/{idCompra}/parcelas/{numero}/liquidacao")
    @Operation(summary = "Liquida uma parcela de forma idempotente e auditavel")
    ParcelaRecebivel liquidar(
            @RequestHeader("X-Empresa-Id") UUID idEmpresa,
            @PathVariable UUID idCompra,
            @PathVariable @Min(1) @Max(12) int numero,
            @Valid @RequestBody LiquidacaoParcela requisicao) {
        return parcelas.liquidar(idEmpresa, idCompra, numero, requisicao);
    }
}
