package br.com.orquestrapay.checkout.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import br.com.orquestrapay.checkout.service.ServicoCheckout;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/compras")
public class ControladorCompras {

    private final ServicoCheckout checkout;

    public ControladorCompras(ServicoCheckout checkout) {
        this.checkout = checkout;
    }

    @PostMapping
    @Operation(summary = "Inicia uma compra idempotente e sua saga distribuida")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "202",
                    description = "Compra aceita para processamento assincrono",
                    content = @Content(schema = @Schema(implementation = RespostaCompra.class))),
            @ApiResponse(responseCode = "400", description = "Requisicao invalida"),
            @ApiResponse(responseCode = "409", description = "Conflito de idempotencia"),
            @ApiResponse(responseCode = "429", description = "Limite de admissao atingido")
    })
    ResponseEntity<RespostaCompra> iniciar(
            @Parameter(example = "00000000-0000-0000-0000-000000000001")
            @RequestHeader("X-Empresa-Id") UUID idEmpresa,
            @Parameter(example = "compra-001")
            @RequestHeader("Idempotency-Key") String chaveIdempotencia,
            @Valid @RequestBody NovaCompra requisicao) {
        var resultado = checkout.iniciar(idEmpresa, chaveIdempotencia, requisicao);
        return ResponseEntity.accepted()
                .location(URI.create("/api/v1/compras/" + resultado.compra().idCompra()))
                .header("Idempotency-Replayed", Boolean.toString(resultado.repetida()))
                .body(resultado.compra());
    }

    @GetMapping("/{idCompra}")
    @Operation(summary = "Consulta o estado atual da compra")
    RespostaCompra buscar(
            @RequestHeader("X-Empresa-Id") UUID idEmpresa,
            @PathVariable UUID idCompra) {
        return checkout.buscar(idEmpresa, idCompra);
    }

    @GetMapping("/{idCompra}/historico")
    @Operation(summary = "Explica cada decisao e compensacao da saga")
    List<RegistroHistorico> historico(
            @RequestHeader("X-Empresa-Id") UUID idEmpresa,
            @PathVariable UUID idCompra) {
        return checkout.buscarHistorico(idEmpresa, idCompra);
    }
}
