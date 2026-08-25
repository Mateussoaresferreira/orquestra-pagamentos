package br.com.orquestrapay.platform.security;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties("orquestrapay.criptografia")
public record PropriedadesCriptografia(
        String chaveTokenBase64,
        String identificadorChaveAtiva,
        Map<String, String> chaves,
        String chaveImpressaoBase64) {

    @ConstructorBinding
    public PropriedadesCriptografia {
        identificadorChaveAtiva = identificadorChaveAtiva == null
                || identificadorChaveAtiva.isBlank()
                ? "v1"
                : identificadorChaveAtiva;
        if (!identificadorChaveAtiva.matches("[A-Za-z0-9_-]{1,32}")) {
            throw new IllegalArgumentException("O identificador da chave ativa e invalido");
        }

        var chavesConfiguradas = new LinkedHashMap<String, String>();
        if (chaves != null) {
            chavesConfiguradas.putAll(chaves);
        }
        if (chaveTokenBase64 != null && !chaveTokenBase64.isBlank()) {
            chavesConfiguradas.putIfAbsent("v1", chaveTokenBase64);
            chavesConfiguradas.putIfAbsent(identificadorChaveAtiva, chaveTokenBase64);
        }
        if (!chavesConfiguradas.containsKey(identificadorChaveAtiva)) {
            throw new IllegalArgumentException("A chave ativa deve existir no keyring");
        }
        for (var entrada : chavesConfiguradas.entrySet()) {
            if (!entrada.getKey().matches("[A-Za-z0-9_-]{1,32}")
                    || entrada.getValue() == null
                    || entrada.getValue().isBlank()) {
                throw new IllegalArgumentException("O keyring de criptografia possui uma entrada invalida");
            }
        }
        chaves = Map.copyOf(chavesConfiguradas);

        if (chaveImpressaoBase64 == null || chaveImpressaoBase64.isBlank()) {
            chaveImpressaoBase64 = chavesConfiguradas.getOrDefault(
                    "v1",
                    chavesConfiguradas.get(identificadorChaveAtiva));
        }
    }

    public PropriedadesCriptografia(String chaveTokenBase64) {
        this(chaveTokenBase64, "v1", Map.of(), null);
    }
}
