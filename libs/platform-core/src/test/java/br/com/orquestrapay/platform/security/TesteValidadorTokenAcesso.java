package br.com.orquestrapay.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class TesteValidadorTokenAcesso {

    private final ValidadorTokenAcesso validador = new ValidadorTokenAcesso("cliente-orquestrapay");

    @Test
    void deveAceitarTokenDeAcessoEmitidoParaOCliente() {
        var resultado = validador.validate(token("access", "cliente-orquestrapay", List.of()));

        assertThat(resultado.hasErrors()).isFalse();
    }

    @Test
    void deveAceitarClienteInformadoNaAudiencia() {
        var resultado = validador.validate(token("access", null, List.of("cliente-orquestrapay")));

        assertThat(resultado.hasErrors()).isFalse();
    }

    @Test
    void deveRecusarTokenDeIdentidade() {
        var resultado = validador.validate(token("id", "cliente-orquestrapay", List.of()));

        assertThat(resultado.hasErrors()).isTrue();
    }

    @Test
    void deveRecusarTokenDeOutroCliente() {
        var resultado = validador.validate(token("access", "outro-cliente", List.of()));

        assertThat(resultado.hasErrors()).isTrue();
    }

    private Jwt token(String uso, String clienteId, List<String> audiencia) {
        var construtor = Jwt.withTokenValue("token-de-teste")
                .header("alg", "RS256")
                .claim("token_use", uso)
                .audience(audiencia);
        if (clienteId != null) {
            construtor.claim("client_id", clienteId);
        }
        return construtor.build();
    }
}
