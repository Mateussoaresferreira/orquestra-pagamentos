package br.com.orquestrapay.provider.config;

import br.com.orquestrapay.provider.security.FiltroChaveApiProvedor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
@EnableConfigurationProperties(PropriedadesAutenticacaoProvedor.class)
public class ConfiguracaoAutenticacaoProvedor {

    @Bean
    FilterRegistrationBean<FiltroChaveApiProvedor> filtroChaveApiProvedor(
            PropriedadesAutenticacaoProvedor propriedades) {
        var registro = new FilterRegistrationBean<>(new FiltroChaveApiProvedor(propriedades));
        registro.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registro.addUrlPatterns("/*");
        return registro;
    }
}
