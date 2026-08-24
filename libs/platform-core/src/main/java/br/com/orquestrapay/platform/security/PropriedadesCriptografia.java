package br.com.orquestrapay.platform.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("orquestrapay.criptografia")
public record PropriedadesCriptografia(String chaveTokenBase64) {

    public PropriedadesCriptografia {
        if (chaveTokenBase64 == null || chaveTokenBase64.isBlank()) {
            throw new IllegalArgumentException("A chave de criptografia dos tokens e obrigatoria");
        }
    }
}
