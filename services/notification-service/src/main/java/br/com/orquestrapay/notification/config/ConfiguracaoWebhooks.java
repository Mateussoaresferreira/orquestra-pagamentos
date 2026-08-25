package br.com.orquestrapay.notification.config;

import java.net.http.HttpClient;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class ConfiguracaoWebhooks {

    @Bean("clienteWebhooks")
    RestClient clienteWebhooks(PropriedadesWebhooks propriedades) {
        HttpClient cliente = HttpClient.newBuilder()
                .connectTimeout(propriedades.tempoConexao())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        var fabrica = new JdkClientHttpRequestFactory(cliente);
        fabrica.setReadTimeout(propriedades.tempoResposta());
        return RestClient.builder().requestFactory(fabrica).build();
    }
}
