package br.com.orquestrapay.payment.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import br.com.orquestrapay.contracts.MetodoPagamento;
import br.com.orquestrapay.payment.api.PedidoAutorizacaoProvedor;
import br.com.orquestrapay.payment.config.PropriedadesProvedor;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class TesteClienteProvedor {

    @Test
    void deveIsolarChamadasConcorrentesAntesDeConsumirACota() throws Exception {
        var entrouNoProvedor = new CountDownLatch(1);
        var liberarProvedor = new CountDownLatch(1);
        var servidor = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 2);
        try (var executorHttp = Executors.newVirtualThreadPerTaskExecutor();
             var chamadas = Executors.newFixedThreadPool(2)) {
            servidor.createContext(
                    "/api/v1/autorizacoes",
                    requisicao -> responderQuandoLiberado(requisicao, entrouNoProvedor, liberarProvedor));
            servidor.setExecutor(executorHttp);
            servidor.start();

            PropriedadesProvedor propriedades = new PropriedadesProvedor(
                    URI.create("http://127.0.0.1:" + servidor.getAddress().getPort()),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(5),
                    "chave-api-provedor-para-testes",
                    "segredo-webhook-provedor-teste",
                    10,
                    Set.of(MetodoPagamento.CARTAO),
                    1,
                    100,
                    Duration.ofSeconds(1));
            var limitador = mock(LimitadorChamadasProvedor.class);
            when(limitador.consumir("principal", propriedades))
                    .thenReturn(new LimitadorChamadasProvedor.ResultadoCota(true, 99, 0));
            Bulkhead bulkhead = Bulkhead.of(
                    "provedor-principal",
                    BulkheadConfig.custom().maxConcurrentCalls(1).maxWaitDuration(Duration.ZERO).build());
            var repeticoes = RetryRegistry.of(RetryConfig.custom().maxAttempts(1).build());
            var cliente = new ClienteProvedor(
                    "principal",
                    propriedades,
                    RestClient.builder()
                            .baseUrl(propriedades.url().toString())
                            .defaultHeader("X-Provedor-Api-Key", propriedades.chaveApi())
                            .build(),
                    CircuitBreakerRegistry.ofDefaults(),
                    repeticoes,
                    bulkhead,
                    limitador,
                    new SimpleMeterRegistry());
            var pedido = new PedidoAutorizacaoProvedor(
                    UUID.randomUUID(),
                    new BigDecimal("49.90"),
                    "BRL",
                    "tok_pagamento_teste");

            var primeira = chamadas.submit(() -> cliente.autorizar(pedido));
            assertThat(entrouNoProvedor.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> cliente.autorizar(pedido))
                    .isInstanceOf(ExcecaoComunicacaoProvedor.class)
                    .hasCauseInstanceOf(BulkheadFullException.class);
            verify(limitador).consumir("principal", propriedades);

            liberarProvedor.countDown();
            assertThat(primeira.get(5, TimeUnit.SECONDS).aprovada()).isTrue();
        } finally {
            liberarProvedor.countDown();
            servidor.stop(0);
        }
    }

    private void responderQuandoLiberado(
            HttpExchange requisicao,
            CountDownLatch entrou,
            CountDownLatch liberar) throws IOException {
        try (requisicao) {
            entrou.countDown();
            if (!liberar.await(5, TimeUnit.SECONDS)) {
                requisicao.sendResponseHeaders(504, -1);
                return;
            }
            byte[] corpo = "{\"aprovada\":true,\"idAutorizacao\":\"aut-1\",\"motivo\":\"Aprovado\"}"
                    .getBytes(StandardCharsets.UTF_8);
            requisicao.getResponseHeaders().set("Content-Type", "application/json");
            requisicao.sendResponseHeaders(200, corpo.length);
            requisicao.getResponseBody().write(corpo);
        } catch (InterruptedException excecao) {
            Thread.currentThread().interrupt();
            requisicao.sendResponseHeaders(503, -1);
        }
    }
}
