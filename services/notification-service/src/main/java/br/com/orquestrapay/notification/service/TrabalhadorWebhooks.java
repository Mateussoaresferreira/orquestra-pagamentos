package br.com.orquestrapay.notification.service;

import java.time.Clock;
import java.util.concurrent.Executors;

import br.com.orquestrapay.platform.security.AssinaturaHmac;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class TrabalhadorWebhooks {

    private final ServicoFilaWebhooks fila;
    private final ValidadorUrlWebhook validadorUrl;
    private final RestClient http;
    private final Clock relogio;

    public TrabalhadorWebhooks(
            ServicoFilaWebhooks fila,
            ValidadorUrlWebhook validadorUrl,
            @Qualifier("clienteWebhooks") RestClient http,
            Clock relogio) {
        this.fila = fila;
        this.validadorUrl = validadorUrl;
        this.http = http;
        this.relogio = relogio;
    }

    @Scheduled(fixedDelayString = "${orquestrapay.webhooks.intervalo:1000}")
    public void enviarPendentes() {
        var entregas = fila.reivindicar();
        if (entregas.isEmpty()) {
            return;
        }
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            entregas.forEach(entrega -> executor.submit(() -> enviar(entrega)));
        }
    }

    private void enviar(ServicoFilaWebhooks.EntregaWebhook entrega) {
        var dados = entrega.dados();
        try {
            var url = validadorUrl.validar(dados.url());
            long timestamp = relogio.instant().getEpochSecond();
            String assinatura = AssinaturaHmac.assinar(
                    entrega.segredo(),
                    timestamp + "." + dados.conteudo());
            int status = http.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Orquestra-Event-Id", dados.idEvento().toString())
                    .header("X-Orquestra-Timestamp", Long.toString(timestamp))
                    .header("X-Orquestra-Signature", assinatura)
                    .body(dados.conteudo())
                    .retrieve()
                    .toBodilessEntity()
                    .getStatusCode()
                    .value();
            fila.confirmar(entrega, status);
        } catch (RestClientResponseException excecao) {
            fila.falhar(
                    entrega,
                    excecao.getStatusCode().value(),
                    "O destino respondeu com status nao exitoso");
        } catch (RuntimeException excecao) {
            fila.falhar(
                    entrega,
                    null,
                    "Falha de transporte: " + excecao.getClass().getSimpleName());
        }
    }
}
