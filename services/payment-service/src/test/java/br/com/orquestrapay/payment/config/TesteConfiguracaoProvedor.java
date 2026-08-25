package br.com.orquestrapay.payment.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;

import br.com.orquestrapay.payment.integration.ExcecaoRequisicaoProvedor;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

class TesteConfiguracaoProvedor {

    @Test
    void deveInterromperARequisicaoQuandoOProvedorExcederOTempoDeLeitura() throws Exception {
        var servidor = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            servidor.createContext("/teste", this::responderComAtraso);
            servidor.setExecutor(executor);
            servidor.start();

            var propriedades = new PropriedadesProvedor(
                    URI.create("http://127.0.0.1:" + servidor.getAddress().getPort()),
                    Duration.ofSeconds(1),
                    Duration.ofMillis(100),
                    "chave-api-provedor-para-testes");
            var cliente = new ConfiguracaoProvedor().clienteHttpProvedor(propriedades);

            assertThatThrownBy(() -> cliente.get().uri("/teste").retrieve().body(String.class))
                    .isInstanceOf(ResourceAccessException.class);
        } finally {
            servidor.stop(0);
        }
    }

    @Test
    void deveFalharRapidoQuandoUmTempoLimiteForInvalido() {
        assertThatThrownBy(() -> new PropriedadesProvedor(
                URI.create("http://localhost:8090"),
                Duration.ZERO,
                Duration.ofSeconds(1),
                "chave-api-provedor-para-testes"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tempoLimiteConexao");
    }

    @Test
    void deveRejeitarChaveDeApiFraca() {
        assertThatThrownBy(() -> new PropriedadesProvedor(
                URI.create("http://localhost:8090"),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                "curta"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("24 caracteres");
    }

    @Test
    void deveClassificarErroQuatrocentosNaoTransitorioComoRequisicaoRejeitada() throws Exception {
        var servidor = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                1);
        servidor.createContext("/teste", requisicao -> {
            requisicao.sendResponseHeaders(422, -1);
            requisicao.close();
        });
        servidor.start();
        try {
            var propriedades = new PropriedadesProvedor(
                    URI.create("http://127.0.0.1:" + servidor.getAddress().getPort()),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1),
                    "chave-api-provedor-para-testes");
            var cliente = new ConfiguracaoProvedor().clienteHttpProvedor(propriedades);

            assertThatThrownBy(() -> cliente.get().uri("/teste").retrieve().toBodilessEntity())
                    .isInstanceOf(ExcecaoRequisicaoProvedor.class)
                    .extracting(excecao -> ((ExcecaoRequisicaoProvedor) excecao).statusHttp())
                    .isEqualTo(422);
        } finally {
            servidor.stop(0);
        }
    }

    private void responderComAtraso(HttpExchange requisicao) throws IOException {
        try (requisicao) {
            Thread.sleep(500);
            byte[] corpo = "OK".getBytes(StandardCharsets.US_ASCII);
            requisicao.sendResponseHeaders(200, corpo.length);
            requisicao.getResponseBody().write(corpo);
        } catch (InterruptedException excecao) {
            Thread.currentThread().interrupt();
        } catch (IOException ignorada) {
            // O cliente pode fechar a conexao depois que o timeout esperado ocorrer.
        }
    }
}
