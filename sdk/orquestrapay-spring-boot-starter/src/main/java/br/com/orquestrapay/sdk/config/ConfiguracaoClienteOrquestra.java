package br.com.orquestrapay.sdk.config;

import br.com.orquestrapay.sdk.client.ClienteOrquestraPay;
import br.com.orquestrapay.sdk.security.FornecedorTokenAcesso;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

@AutoConfiguration
@ConditionalOnClass(RestClient.class)
@EnableConfigurationProperties(PropriedadesClienteOrquestra.class)
public class ConfiguracaoClienteOrquestra {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "orquestrapay.cliente", name = "id-empresa")
    ClienteOrquestraPay clienteOrquestraPay(
            PropriedadesClienteOrquestra propriedades,
            ObjectProvider<FornecedorTokenAcesso> fornecedorToken) {
        return new ClienteOrquestraPay(
                propriedades,
                fornecedorToken.getIfAvailable());
    }
}
