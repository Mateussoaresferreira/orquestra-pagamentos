package br.com.orquestrapay.ledger.api;

import java.util.UUID;

import br.com.orquestrapay.ledger.service.ServicoRazao;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transacoes-contabeis")
public class ControladorRazao {

    private final ServicoRazao razao;

    public ControladorRazao(ServicoRazao razao) {
        this.razao = razao;
    }

    @GetMapping("/compras/{idCompra}")
    @Operation(summary = "Consulta a transacao e suas partidas imutaveis")
    RespostaTransacaoContabil buscar(
            @RequestHeader("X-Empresa-Id") UUID idEmpresa,
            @PathVariable UUID idCompra) {
        return razao.buscar(idEmpresa, idCompra);
    }
}
