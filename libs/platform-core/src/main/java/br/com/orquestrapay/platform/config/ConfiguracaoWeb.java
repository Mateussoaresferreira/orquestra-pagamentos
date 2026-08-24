package br.com.orquestrapay.platform.config;

import br.com.orquestrapay.platform.web.FiltroCabecalhosSeguranca;
import br.com.orquestrapay.platform.web.FiltroTamanhoRequisicao;
import br.com.orquestrapay.platform.web.PropriedadesWeb;
import br.com.orquestrapay.platform.web.TratadorExcecoes;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(PropriedadesWeb.class)
public class ConfiguracaoWeb {

    @Bean
    FilterRegistrationBean<FiltroCabecalhosSeguranca> filtroCabecalhosSeguranca() {
        var registro = new FilterRegistrationBean<>(new FiltroCabecalhosSeguranca());
        registro.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registro.addUrlPatterns("/*");
        return registro;
    }

    @Bean
    TratadorExcecoes tratadorExcecoes() {
        return new TratadorExcecoes();
    }

    @Bean
    FiltroTamanhoRequisicao filtroTamanhoRequisicao(PropriedadesWeb propriedades) {
        return new FiltroTamanhoRequisicao(propriedades);
    }
}
