package br.com.orquestrapay.notification.api;

import java.util.List;
import java.util.UUID;

import br.com.orquestrapay.notification.service.ServicoNotificacao;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notificacoes")
public class ControladorNotificacoes {

    private final ServicoNotificacao notificacoes;

    public ControladorNotificacoes(ServicoNotificacao notificacoes) {
        this.notificacoes = notificacoes;
    }

    @GetMapping("/compras/{idCompra}")
    @Operation(summary = "Consulta as notificacoes geradas para uma compra")
    List<RespostaNotificacao> buscar(
            @RequestHeader("X-Empresa-Id") UUID idEmpresa,
            @PathVariable UUID idCompra) {
        return notificacoes.buscar(idEmpresa, idCompra);
    }
}
