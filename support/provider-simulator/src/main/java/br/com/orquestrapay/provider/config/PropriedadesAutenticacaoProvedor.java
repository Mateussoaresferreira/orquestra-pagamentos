package br.com.orquestrapay.provider.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("provedor.autenticacao")
public record PropriedadesAutenticacaoProvedor(String chaveApi) {

    public PropriedadesAutenticacaoProvedor {
        if (chaveApi == null || chaveApi.length() < 24) {
            throw new IllegalArgumentException("A chave de API do provedor deve ter ao menos 24 caracteres");
        }
    }
}
