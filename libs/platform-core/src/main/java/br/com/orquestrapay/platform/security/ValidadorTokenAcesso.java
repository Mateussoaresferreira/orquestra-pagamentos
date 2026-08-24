package br.com.orquestrapay.platform.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

public class ValidadorTokenAcesso implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error ERRO_TIPO_TOKEN = new OAuth2Error(
            "token_invalido",
            "A API aceita somente tokens de acesso",
            null);
    private static final OAuth2Error ERRO_CLIENTE = new OAuth2Error(
            "token_invalido",
            "O token nao foi emitido para este cliente",
            null);

    private final String clienteId;

    public ValidadorTokenAcesso(String clienteId) {
        this.clienteId = clienteId;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        if (!"access".equals(jwt.getClaimAsString("token_use"))) {
            return OAuth2TokenValidatorResult.failure(ERRO_TIPO_TOKEN);
        }
        if (clienteId != null && !clienteId.isBlank()) {
            boolean clienteCompativel = clienteId.equals(jwt.getClaimAsString("client_id"))
                    || jwt.getAudience().contains(clienteId);
            if (!clienteCompativel) {
                return OAuth2TokenValidatorResult.failure(ERRO_CLIENTE);
            }
        }
        return OAuth2TokenValidatorResult.success();
    }
}
