package br.com.orquestrapay.payment.config;

import java.util.LinkedHashMap;

import br.com.orquestrapay.payment.integration.CatalogoProvedores;
import br.com.orquestrapay.payment.integration.ClienteProvedor;
import br.com.orquestrapay.payment.integration.ExcecaoRequisicaoProvedor;
import br.com.orquestrapay.payment.integration.LimitadorChamadasProvedor;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(PropriedadesPagamentos.class)
public class ConfiguracaoProvedor {

    @Bean
    CatalogoProvedores catalogoProvedores(
            PropriedadesPagamentos propriedades,
            CircuitBreakerRegistry circuitos,
            RetryRegistry repeticoes,
            BulkheadRegistry bulkheads,
            LimitadorChamadasProvedor limitador,
            MeterRegistry metricas) {
        var provedores = new LinkedHashMap<String, ClienteProvedor>();
        propriedades.provedores().forEach((nome, configuracao) -> provedores.put(
                nome,
                criarCliente(nome, configuracao, circuitos, repeticoes, bulkheads, limitador, metricas)));
        return new CatalogoProvedores(provedores);
    }

    private ClienteProvedor criarCliente(
            String nome,
            PropriedadesProvedor propriedades,
            CircuitBreakerRegistry circuitos,
            RetryRegistry repeticoes,
            BulkheadRegistry bulkheads,
            LimitadorChamadasProvedor limitador,
            MeterRegistry metricas) {
        RestClient http = clienteHttpProvedor(propriedades);
        var configuracaoBulkhead = BulkheadConfig.custom()
                .maxConcurrentCalls(propriedades.maximoConcorrente())
                .maxWaitDuration(java.time.Duration.ZERO)
                .build();
        return new ClienteProvedor(
                nome,
                propriedades,
                http,
                circuitos,
                repeticoes,
                bulkheads.bulkhead("provedor-" + nome, configuracaoBulkhead),
                limitador,
                metricas);
    }

    RestClient clienteHttpProvedor(PropriedadesProvedor propriedades) {
        var fabricaRequisicoes = new SimpleClientHttpRequestFactory();
        fabricaRequisicoes.setConnectTimeout(propriedades.tempoLimiteConexao());
        fabricaRequisicoes.setReadTimeout(propriedades.tempoLimiteLeitura());

        return RestClient.builder()
                .baseUrl(propriedades.url().toString())
                .requestFactory(fabricaRequisicoes)
                .defaultHeader("X-Provedor-Api-Key", propriedades.chaveApi())
                .defaultStatusHandler(
                        status -> status.is4xxClientError()
                                && status.value() != 408
                                && status.value() != 429,
                        (requisicao, resposta) -> {
                            throw new ExcecaoRequisicaoProvedor(
                                    resposta.getStatusCode().value());
                        })
                .build();
    }
}
