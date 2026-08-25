package br.com.orquestrapay.platform.security;

import java.util.Collection;
import java.util.Set;

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

    private final Set<String> clientesId;

    public ValidadorTokenAcesso(String clienteId) {
        this(clienteId == null || clienteId.isBlank() ? Set.of() : Set.of(clienteId));
    }

    public ValidadorTokenAcesso(Collection<String> clientesId) {
        this.clientesId = clientesId == null ? Set.of() : Set.copyOf(clientesId);
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        if (!"access".equals(jwt.getClaimAsString("token_use"))) {
            return OAuth2TokenValidatorResult.failure(ERRO_TIPO_TOKEN);
        }
        if (!clientesId.isEmpty()) {
            String clienteToken = jwt.getClaimAsString("client_id");
            boolean clienteCompativel = clienteToken != null && clientesId.contains(clienteToken)
                    || jwt.getAudience().stream().anyMatch(clientesId::contains);
            if (!clienteCompativel) {
                return OAuth2TokenValidatorResult.failure(ERRO_CLIENTE);
            }
        }
        return OAuth2TokenValidatorResult.success();
    }
}
