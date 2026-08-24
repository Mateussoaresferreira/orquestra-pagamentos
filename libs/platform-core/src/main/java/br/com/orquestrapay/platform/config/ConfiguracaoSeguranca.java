package br.com.orquestrapay.platform.config;

import br.com.orquestrapay.platform.security.ConversorAutoridadesJwt;
import br.com.orquestrapay.platform.security.FiltroEmpresaAutenticada;
import br.com.orquestrapay.platform.security.PropriedadesSeguranca;
import br.com.orquestrapay.platform.security.ValidadorTokenAcesso;
import jakarta.servlet.DispatcherType;
import tools.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

@AutoConfiguration
@ConditionalOnClass(SecurityFilterChain.class)
@EnableConfigurationProperties(PropriedadesSeguranca.class)
public class ConfiguracaoSeguranca {

    @Bean
    @ConditionalOnProperty(
            name = "orquestrapay.seguranca.habilitada",
            havingValue = "false")
    SecurityFilterChain segurancaLocal(HttpSecurity http) throws Exception {
        return http
                .csrf(configuracao -> configuracao.disable())
                .authorizeHttpRequests(regras -> regras.anyRequest().permitAll())
                .build();
    }

    @Bean
    @ConditionalOnProperty(
            name = "orquestrapay.seguranca.habilitada",
            havingValue = "true",
            matchIfMissing = true)
    JwtDecoder decodificadorJwt(PropriedadesSeguranca propriedades) {
        if (propriedades.emissor() == null || propriedades.emissor().isBlank()) {
            throw new IllegalStateException(
                    "O emissor OIDC e obrigatorio quando a seguranca esta habilitada");
        }
        var decodificador = NimbusJwtDecoder.withIssuerLocation(propriedades.emissor()).build();
        var validadores = new DelegatingOAuth2TokenValidator<Jwt>(
                JwtValidators.createDefaultWithIssuer(propriedades.emissor()),
                new ValidadorTokenAcesso(propriedades.clienteId()));
        decodificador.setJwtValidator(validadores);
        return decodificador;
    }

    @Bean
    @ConditionalOnProperty(
            name = "orquestrapay.seguranca.habilitada",
            havingValue = "true",
            matchIfMissing = true)
    FiltroEmpresaAutenticada filtroEmpresaAutenticada(
            PropriedadesSeguranca propriedades,
            ObjectMapper json) {
        return new FiltroEmpresaAutenticada(propriedades, json);
    }

    @Bean
    @ConditionalOnProperty(
            name = "orquestrapay.seguranca.habilitada",
            havingValue = "true",
            matchIfMissing = true)
    SecurityFilterChain segurancaProducao(
            HttpSecurity http,
            FiltroEmpresaAutenticada filtroEmpresa,
            PropriedadesSeguranca propriedades) throws Exception {
        var conversor = new JwtAuthenticationConverter();
        conversor.setJwtGrantedAuthoritiesConverter(new ConversorAutoridadesJwt(propriedades));
        return http
                .csrf(configuracao -> configuracao.disable())
                .sessionManagement(configuracao -> configuracao
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(regras -> regras
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers(
                                "/actuator/health/**")
                        .permitAll()
                        .requestMatchers("/actuator/**")
                        .hasAuthority("ROLE_OBSERVABILIDADE")
                        .requestMatchers(HttpMethod.POST, "/api/v1/compras")
                        .hasAuthority("SCOPE_compras:escrever")
                        .requestMatchers(HttpMethod.GET, "/api/v1/compras/**")
                        .hasAuthority("SCOPE_compras:ler")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/estoques/**")
                        .hasAnyAuthority("SCOPE_estoque:escrever", "ROLE_OPERADOR")
                        .requestMatchers(HttpMethod.GET, "/api/v1/estoques/**")
                        .hasAnyAuthority("SCOPE_estoque:ler", "ROLE_OPERADOR", "ROLE_AUDITOR")
                        .requestMatchers(HttpMethod.GET, "/api/v1/analises-risco/**")
                        .hasAnyAuthority("SCOPE_risco:ler", "ROLE_ANALISTA_RISCO", "ROLE_AUDITOR")
                        .requestMatchers(HttpMethod.GET, "/api/v1/pagamentos/**")
                        .hasAnyAuthority("SCOPE_pagamentos:ler", "ROLE_FINANCEIRO", "ROLE_AUDITOR")
                        .requestMatchers(HttpMethod.POST, "/api/v1/conciliacoes")
                        .hasAnyAuthority("SCOPE_pagamentos:conciliar", "ROLE_FINANCEIRO")
                        .requestMatchers(HttpMethod.GET, "/api/v1/transacoes-contabeis/**")
                        .hasAnyAuthority("SCOPE_razao:ler", "ROLE_FINANCEIRO", "ROLE_AUDITOR")
                        .requestMatchers(HttpMethod.GET, "/api/v1/notificacoes/**")
                        .hasAnyAuthority("SCOPE_notificacoes:ler", "ROLE_OPERADOR", "ROLE_AUDITOR")
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                        .hasAnyAuthority("ROLE_DESENVOLVEDOR", "ROLE_AUDITOR")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(configuracao -> configuracao
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(conversor)))
                .addFilterAfter(filtroEmpresa, BearerTokenAuthenticationFilter.class)
                .build();
    }
}
