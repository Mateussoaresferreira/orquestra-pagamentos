package br.com.orquestrapay.platform.config;

import br.com.orquestrapay.platform.security.PropriedadesCriptografia;
import br.com.orquestrapay.platform.security.ProtecaoTokenPagamento;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnProperty("orquestrapay.criptografia.chave-token-base64")
@EnableConfigurationProperties(PropriedadesCriptografia.class)
public class ConfiguracaoCriptografia {

    @Bean
    @ConditionalOnMissingBean
    ProtecaoTokenPagamento protecaoTokenPagamento(PropriedadesCriptografia propriedades) {
        return new ProtecaoTokenPagamento(propriedades);
    }
}
