package br.com.orquestrapay.provider.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(PropriedadesSimulador.class)
public class ConfiguracaoSimulador {

    @Bean
    RestClient clienteWebhook() {
        return RestClient.create();
    }
}
