package br.com.orquestrapay.checkout.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PropriedadesRetencaoCheckout.class)
public class ConfiguracaoRetencaoCheckout {
}
