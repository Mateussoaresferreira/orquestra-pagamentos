package br.com.orquestrapay.notification.api;

import java.util.UUID;

import br.com.orquestrapay.notification.service.ServicoWebhooksEmpresa;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/webhooks")
public class ControladorWebhooksEmpresa {

    private final ServicoWebhooksEmpresa webhooks;

    public ControladorWebhooksEmpresa(ServicoWebhooksEmpresa webhooks) {
        this.webhooks = webhooks;
    }

    @PutMapping("/configuracao")
    @Operation(summary = "Cadastra ou rotaciona a configuracao de webhook da empresa")
    RespostaConfiguracaoWebhook configurar(
            @RequestHeader("X-Empresa-Id") UUID idEmpresa,
            @Valid @RequestBody ConfiguracaoWebhookEntrada entrada) {
        return webhooks.configurar(idEmpresa, entrada);
    }

    @GetMapping("/configuracao")
    @Operation(summary = "Consulta a configuracao sem revelar o segredo HMAC")
    RespostaConfiguracaoWebhook buscarConfiguracao(
            @RequestHeader("X-Empresa-Id") UUID idEmpresa) {
        return webhooks.buscarConfiguracao(idEmpresa);
    }

    @DeleteMapping("/configuracao")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Desabilita o envio de novos webhooks")
    void desabilitar(@RequestHeader("X-Empresa-Id") UUID idEmpresa) {
        webhooks.desabilitar(idEmpresa);
    }

    @GetMapping("/entregas")
    @Operation(summary = "Lista o historico auditavel de entregas")
    PaginaEntregasWebhook listar(
            @RequestHeader("X-Empresa-Id") UUID idEmpresa,
            @RequestParam(defaultValue = "0") @Min(0) int pagina,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int tamanho) {
        return webhooks.listar(idEmpresa, pagina, tamanho);
    }

    @PostMapping("/entregas/{idEntrega}/reprocessar")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Reabre uma entrega que terminou em falha definitiva")
    void reprocessar(
            @RequestHeader("X-Empresa-Id") UUID idEmpresa,
            @PathVariable UUID idEntrega) {
        webhooks.reprocessar(idEmpresa, idEntrega);
    }
}
