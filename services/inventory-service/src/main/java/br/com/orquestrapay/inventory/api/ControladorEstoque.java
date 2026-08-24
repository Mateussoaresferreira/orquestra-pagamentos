package br.com.orquestrapay.inventory.api;

import java.util.UUID;

import br.com.orquestrapay.inventory.service.ServicoEstoque;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/estoques")
public class ControladorEstoque {

    private final ServicoEstoque estoque;

    public ControladorEstoque(ServicoEstoque estoque) {
        this.estoque = estoque;
    }

    @PutMapping("/{idProduto}")
    @Operation(summary = "Define o saldo disponivel de um produto")
    RespostaEstoque definir(
            @RequestHeader("X-Empresa-Id") UUID idEmpresa,
            @PathVariable UUID idProduto,
            @Valid @RequestBody AjusteEstoque ajuste) {
        return estoque.definir(idEmpresa, idProduto, ajuste);
    }

    @GetMapping("/{idProduto}")
    @Operation(summary = "Consulta os saldos disponivel e reservado")
    RespostaEstoque buscar(
            @RequestHeader("X-Empresa-Id") UUID idEmpresa,
            @PathVariable UUID idProduto) {
        return estoque.buscar(idEmpresa, idProduto);
    }
}
