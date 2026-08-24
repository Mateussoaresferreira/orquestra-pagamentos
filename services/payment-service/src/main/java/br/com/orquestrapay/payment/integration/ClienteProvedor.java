package br.com.orquestrapay.payment.integration;

import br.com.orquestrapay.payment.api.PedidoAutorizacaoProvedor;
import br.com.orquestrapay.payment.api.PedidoEstornoProvedor;
import br.com.orquestrapay.payment.api.RespostaAutorizacaoProvedor;
import br.com.orquestrapay.payment.api.RespostaEstornoProvedor;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class ClienteProvedor {

    private final RestClient http;

    public ClienteProvedor(RestClient clienteHttpProvedor) {
        this.http = clienteHttpProvedor;
    }

    @Retry(name = "provedor-pagamento")
    @CircuitBreaker(name = "provedor-pagamento")
    public RespostaAutorizacaoProvedor autorizar(PedidoAutorizacaoProvedor pedido) {
        return http.post()
                .uri("/api/v1/autorizacoes")
                .header("Idempotency-Key", pedido.idCompra().toString())
                .body(pedido)
                .retrieve()
                .body(RespostaAutorizacaoProvedor.class);
    }

    @Retry(name = "provedor-pagamento")
    @CircuitBreaker(name = "provedor-pagamento")
    public RespostaEstornoProvedor estornar(PedidoEstornoProvedor pedido) {
        return http.post()
                .uri("/api/v1/estornos")
                .header("Idempotency-Key", pedido.idPagamento().toString())
                .body(pedido)
                .retrieve()
                .body(RespostaEstornoProvedor.class);
    }
}
