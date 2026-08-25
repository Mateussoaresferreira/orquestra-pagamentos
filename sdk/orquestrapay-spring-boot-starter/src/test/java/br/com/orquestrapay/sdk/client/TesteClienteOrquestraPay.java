package br.com.orquestrapay.sdk.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import br.com.orquestrapay.sdk.api.MetodoPagamentoCliente;
import br.com.orquestrapay.sdk.api.NovaCompraCliente;
import br.com.orquestrapay.sdk.config.PropriedadesClienteOrquestra;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TesteClienteOrquestraPay {

    private HttpServer servidor;

    @AfterEach
    void encerrarServidor() {
        if (servidor != null) {
            servidor.stop(0);
        }
    }

    @Test
    void deveEnviarEmpresaTokenEIdempotenciaAoCriarCompra() throws IOException {
        UUID idEmpresa = UUID.randomUUID();
        UUID idCompra = UUID.randomUUID();
        var cabecalhoEmpresa = new AtomicReference<String>();
        var cabecalhoToken = new AtomicReference<String>();
        var cabecalhoIdempotencia = new AtomicReference<String>();
        servidor = servidor(exchange -> {
            cabecalhoEmpresa.set(exchange.getRequestHeaders().getFirst("X-Empresa-Id"));
            cabecalhoToken.set(exchange.getRequestHeaders().getFirst("Authorization"));
            cabecalhoIdempotencia.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            responder(exchange, 202, """
                    {
                      "idCompra": "%s",
                      "idEmpresa": "%s",
                      "status": "RECEBIDA",
                      "valorTotal": 89.90,
                      "moeda": "BRL",
                      "metodoPagamento": "CARTAO",
                      "parcelas": 3,
                      "motivo": "Compra recebida",
                      "criadoEm": "2026-08-24T12:00:00Z",
                      "atualizadoEm": "2026-08-24T12:00:00Z"
                    }
                    """.formatted(idCompra, idEmpresa));
        });
        var cliente = cliente(idEmpresa, () -> "token-de-acesso");

        var resposta = cliente.criarCompra(
                "pedido:123",
                new NovaCompraCliente(
                        "cliente-1",
                        "cliente@exemplo.com",
                        "BRL",
                        "BR",
                        "dispositivo-1",
                        "tok_aprovado",
                        java.util.List.of(),
                        MetodoPagamentoCliente.CARTAO,
                        3));

        assertThat(resposta.idCompra()).isEqualTo(idCompra);
        assertThat(resposta.parcelas()).isEqualTo(3);
        assertThat(cabecalhoEmpresa).hasValue(idEmpresa.toString());
        assertThat(cabecalhoToken).hasValue("Bearer token-de-acesso");
        assertThat(cabecalhoIdempotencia).hasValue("pedido:123");
    }

    @Test
    void deveTraduzirErroHttpParaExcecaoDoSdk() throws IOException {
        UUID idEmpresa = UUID.randomUUID();
        servidor = servidor(exchange -> responder(exchange, 409, "{}"));
        var cliente = cliente(idEmpresa, null);

        assertThatThrownBy(() -> cliente.criarCompra(
                        "pedido:conflitante",
                        new NovaCompraCliente(
                                "cliente-1",
                                "cliente@exemplo.com",
                                "BRL",
                                "BR",
                                "dispositivo-1",
                                "tok_aprovado",
                                java.util.List.of(),
                                MetodoPagamentoCliente.CARTAO,
                                1)))
                .isInstanceOf(ExcecaoClienteOrquestra.class)
                .extracting(excecao -> ((ExcecaoClienteOrquestra) excecao).statusHttp())
                .isEqualTo(409);
    }

    private ClienteOrquestraPay cliente(
            UUID idEmpresa,
            br.com.orquestrapay.sdk.security.FornecedorTokenAcesso fornecedorToken) {
        URI url = URI.create("http://127.0.0.1:" + servidor.getAddress().getPort());
        var propriedades = new PropriedadesClienteOrquestra(
                idEmpresa,
                url,
                url,
                url,
                Duration.ofSeconds(2));
        return new ClienteOrquestraPay(propriedades, fornecedorToken);
    }

    private HttpServer servidor(Manipulador manipulador) throws IOException {
        HttpServer criado = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        criado.createContext("/api/v1/compras", exchange -> manipulador.tratar(exchange));
        criado.start();
        return criado;
    }

    private void responder(HttpExchange exchange, int status, String conteudo) throws IOException {
        byte[] bytes = conteudo.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface Manipulador {
        void tratar(HttpExchange exchange) throws IOException;
    }
}
