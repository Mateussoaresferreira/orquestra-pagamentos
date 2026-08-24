package br.com.orquestrapay.risk.api;

import java.util.UUID;

import br.com.orquestrapay.risk.service.ServicoRisco;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/analises-risco")
public class ControladorRisco {

    private final ServicoRisco risco;

    public ControladorRisco(ServicoRisco risco) {
        this.risco = risco;
    }

    @GetMapping("/compras/{idCompra}")
    @Operation(summary = "Consulta os sinais e a pontuacao da analise de risco")
    RespostaAnaliseRisco buscar(
            @RequestHeader("X-Empresa-Id") UUID idEmpresa,
            @PathVariable UUID idCompra) {
        return risco.buscar(idEmpresa, idCompra);
    }
}
