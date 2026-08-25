package br.com.orquestrapay.payment.integration;

import java.util.function.Supplier;

import br.com.orquestrapay.contracts.MetodoPagamento;
import br.com.orquestrapay.payment.api.PedidoAutorizacaoProvedor;
import br.com.orquestrapay.payment.api.PedidoCobrancaPixProvedor;
import br.com.orquestrapay.payment.api.PedidoEstornoProvedor;
import br.com.orquestrapay.payment.api.RespostaAutorizacaoProvedor;
import br.com.orquestrapay.payment.api.RespostaCobrancaPixProvedor;
import br.com.orquestrapay.payment.api.RespostaEstornoProvedor;
import br.com.orquestrapay.payment.config.PropriedadesProvedor;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.web.client.RestClient;

public class ClienteProvedor {

    private final String nome;
    private final PropriedadesProvedor propriedades;
    private final RestClient http;
    private final CircuitBreaker circuito;
    private final Retry repeticao;
    private final Bulkhead bulkhead;
    private final LimitadorChamadasProvedor limitador;
    private final MeterRegistry metricas;

    public ClienteProvedor(
            String nome,
            PropriedadesProvedor propriedades,
            RestClient http,
            CircuitBreakerRegistry circuitos,
            RetryRegistry repeticoes,
            Bulkhead bulkhead,
            LimitadorChamadasProvedor limitador,
            MeterRegistry metricas) {
        this.nome = nome;
        this.propriedades = propriedades;
        this.http = http;
        this.circuito = circuitos.circuitBreaker("provedor-" + nome);
        this.repeticao = repeticoes.retry("provedor-" + nome);
        this.bulkhead = bulkhead;
        this.limitador = limitador;
        this.metricas = metricas;
    }

    public String nome() {
        return nome;
    }

    public int prioridade() {
        return propriedades.prioridade();
    }

    public boolean aceita(MetodoPagamento metodo) {
        return propriedades.metodos().contains(metodo);
    }

    public String segredoWebhook() {
        return propriedades.segredoWebhook();
    }

    public RespostaAutorizacaoProvedor autorizar(PedidoAutorizacaoProvedor pedido) {
        return executar("autorizar", () -> http.post()
                .uri("/api/v1/autorizacoes")
                .header("Idempotency-Key", pedido.idCompra() + ":autorizacao")
                .body(pedido)
                .retrieve()
                .body(RespostaAutorizacaoProvedor.class));
    }

    public RespostaCobrancaPixProvedor criarPix(PedidoCobrancaPixProvedor pedido) {
        return executar("criar-pix", () -> http.post()
                .uri("/api/v1/cobrancas/pix")
                .header("Idempotency-Key", pedido.idCompra() + ":pix")
                .body(pedido)
                .retrieve()
                .body(RespostaCobrancaPixProvedor.class));
    }

    public RespostaEstornoProvedor estornar(PedidoEstornoProvedor pedido) {
        return executar("estornar", () -> http.post()
                .uri("/api/v1/estornos")
                .header("Idempotency-Key", pedido.idPagamento() + ":estorno")
                .body(pedido)
                .retrieve()
                .body(RespostaEstornoProvedor.class));
    }

    private <T> T executar(String operacao, Supplier<T> chamada) {
        Supplier<T> chamadaComCota = () -> {
            var cota = limitador.consumir(nome, propriedades);
            if (!cota.permitido()) {
                throw new ExcecaoCotaProvedor(nome, cota.tentarNovamenteEmMillis());
            }
            return chamada.get();
        };
        Supplier<T> protegida = Bulkhead.decorateSupplier(bulkhead, chamadaComCota);
        protegida = CircuitBreaker.decorateSupplier(circuito, protegida);
        protegida = Retry.decorateSupplier(repeticao, protegida);
        try {
            T resposta = protegida.get();
            if (resposta == null) {
                throw new IllegalStateException("O provedor retornou resposta vazia");
            }
            metricas.counter(
                    "orquestrapay.provedor.chamadas",
                    "provedor", nome,
                    "operacao", operacao,
                    "resultado", "sucesso").increment();
            return resposta;
        } catch (ExcecaoCotaProvedor excecao) {
            metricas.counter(
                    "orquestrapay.provedor.chamadas",
                    "provedor", nome,
                    "operacao", operacao,
                    "resultado", "cota-excedida").increment();
            throw new ExcecaoComunicacaoProvedor(nome, operacao, excecao);
        } catch (ExcecaoRequisicaoProvedor excecao) {
            metricas.counter(
                    "orquestrapay.provedor.chamadas",
                    "provedor", nome,
                    "operacao", operacao,
                    "resultado", "requisicao-rejeitada").increment();
            throw excecao;
        } catch (RuntimeException excecao) {
            metricas.counter(
                    "orquestrapay.provedor.chamadas",
                    "provedor", nome,
                    "operacao", operacao,
                    "resultado", "falha").increment();
            throw new ExcecaoComunicacaoProvedor(nome, operacao, excecao);
        }
    }
}
