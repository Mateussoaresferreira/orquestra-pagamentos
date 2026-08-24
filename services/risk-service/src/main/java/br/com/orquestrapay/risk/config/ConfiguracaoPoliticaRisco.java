package br.com.orquestrapay.risk.config;

import br.com.orquestrapay.risk.domain.PoliticaRisco;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PropriedadesPoliticaRisco.class)
public class ConfiguracaoPoliticaRisco {

    @Bean
    PoliticaRisco politicaRisco(PropriedadesPoliticaRisco propriedades) {
        return propriedades.paraDominio();
    }
}
