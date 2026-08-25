package br.com.orquestrapay.payment.api;

import br.com.orquestrapay.payment.service.ServicoWebhookProvedor;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
@RequestMapping("/api/v1/webhooks/provedores")
public class ControladorWebhookProvedor {

    private final ServicoWebhookProvedor webhooks;

    public ControladorWebhookProvedor(ServicoWebhookProvedor webhooks) {
        this.webhooks = webhooks;
    }

    @PostMapping
    ResponseEntity<Void> receber(
            @RequestHeader("X-Provedor") String provedor,
            @RequestHeader("X-Orquestra-Timestamp") long timestamp,
            @RequestHeader("X-Orquestra-Signature") String assinatura,
            @RequestBody String conteudo) {
        webhooks.processar(provedor, timestamp, assinatura, conteudo);
        return ResponseEntity.noContent().build();
    }
}
