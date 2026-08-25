package br.com.orquestrapay.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class TesteConversorAutoridadesJwt {

    private final ConversorAutoridadesJwt conversor = new ConversorAutoridadesJwt(
            new PropriedadesSeguranca(true, null, null, null, Set.of(), null, null));

    @Test
    void deveCombinarEscoposEGruposDoCognito() {
        Jwt jwt = Jwt.withTokenValue("token-de-teste")
                .header("alg", "none")
                .claim("scope", "compras:ler compras:escrever")
                .claim("cognito:groups", List.of("operador-financeiro", "administrador"))
                .build();

        assertThat(conversor.convert(jwt))
                .extracting(autoridade -> autoridade.getAuthority())
                .containsExactlyInAnyOrder(
                        "SCOPE_compras:ler",
                        "SCOPE_compras:escrever",
                        "ROLE_OPERADOR_FINANCEIRO",
                        "ROLE_ADMINISTRADOR");
    }

    @Test
    void deveAceitarTokenSemGrupos() {
        Jwt jwt = Jwt.withTokenValue("token-de-teste")
                .header("alg", "none")
                .claim("scope", "compras:ler")
                .build();

        assertThat(conversor.convert(jwt))
                .extracting(autoridade -> autoridade.getAuthority())
                .containsExactly("SCOPE_compras:ler");
    }
}
