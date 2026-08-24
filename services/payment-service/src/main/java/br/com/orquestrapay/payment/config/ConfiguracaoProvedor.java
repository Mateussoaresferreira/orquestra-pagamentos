package br.com.orquestrapay.payment.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(PropriedadesProvedor.class)
public class ConfiguracaoProvedor {

    @Bean
    RestClient clienteHttpProvedor(PropriedadesProvedor propriedades) {
        var fabricaRequisicoes = new SimpleClientHttpRequestFactory();
        fabricaRequisicoes.setConnectTimeout(propriedades.tempoLimiteConexao());
        fabricaRequisicoes.setReadTimeout(propriedades.tempoLimiteLeitura());

        return RestClient.builder()
                .baseUrl(propriedades.url().toString())
                .requestFactory(fabricaRequisicoes)
                .defaultHeader("X-Provedor-Api-Key", propriedades.chaveApi())
                .build();
    }
}
