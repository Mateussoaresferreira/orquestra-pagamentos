package br.com.orquestrapay.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.ObjectMapper;

class TesteFiltroEmpresaAutenticada {

    private final PropriedadesSeguranca propriedades = new PropriedadesSeguranca(
            true,
            "custom:empresa_id",
            "cognito:groups",
            "https://emissor.exemplo",
            "cliente-api");
    private final FiltroEmpresaAutenticada filtro = new FiltroEmpresaAutenticada(
            propriedades,
            new ObjectMapper());

    @AfterEach
    void limparContexto() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void deveBloquearAcessoAOutraEmpresa() throws Exception {
        UUID empresaToken = UUID.randomUUID();
        autenticar(empresaToken.toString());
        var requisicao = new MockHttpServletRequest("GET", "/api/v1/compras/qualquer");
        requisicao.addHeader("X-Empresa-Id", UUID.randomUUID().toString());
        var resposta = new MockHttpServletResponse();
        var controladorChamado = new AtomicBoolean();

        filtro.doFilter(requisicao, resposta, (entrada, saida) -> controladorChamado.set(true));

        assertThat(resposta.getStatus()).isEqualTo(403);
        assertThat(resposta.getContentAsString()).contains("empresa-nao-autorizada");
        assertThat(controladorChamado).isFalse();
    }

    @Test
    void devePermitirSomenteAEmpresaDoToken() throws Exception {
        UUID idEmpresa = UUID.randomUUID();
        autenticar(idEmpresa.toString());
        var requisicao = new MockHttpServletRequest("GET", "/api/v1/compras/qualquer");
        requisicao.addHeader("X-Empresa-Id", idEmpresa.toString());
        var resposta = new MockHttpServletResponse();
        var controladorChamado = new AtomicBoolean();

        filtro.doFilter(requisicao, resposta, (entrada, saida) -> controladorChamado.set(true));

        assertThat(resposta.getStatus()).isEqualTo(200);
        assertThat(controladorChamado).isTrue();
    }

    @Test
    void deveBloquearTokenSemEmpresaEmEndpointMultiempresa() throws Exception {
        autenticar(null);
        var requisicao = new MockHttpServletRequest("GET", "/api/v1/compras/qualquer");
        requisicao.addHeader("X-Empresa-Id", UUID.randomUUID().toString());
        var resposta = new MockHttpServletResponse();

        filtro.doFilter(requisicao, resposta, (entrada, saida) -> {
            throw new AssertionError("O controlador nao poderia ser chamado");
        });

        assertThat(resposta.getStatus()).isEqualTo(403);
    }

    @Test
    void deveExigirCabecalhoDeEmpresaEmEndpointMultiempresa() throws Exception {
        autenticar(UUID.randomUUID().toString());
        var requisicao = new MockHttpServletRequest("GET", "/api/v1/compras/qualquer");
        var resposta = new MockHttpServletResponse();

        filtro.doFilter(requisicao, resposta, (entrada, saida) -> {
            throw new AssertionError("O controlador nao poderia ser chamado");
        });

        assertThat(resposta.getStatus()).isEqualTo(400);
        assertThat(resposta.getContentAsString()).contains("empresa-obrigatoria");
    }

    @Test
    void deveIgnorarCabecalhoDeEmpresaForaDaApi() throws Exception {
        autenticar(UUID.randomUUID().toString());
        var requisicao = new MockHttpServletRequest("GET", "/actuator/health");
        var resposta = new MockHttpServletResponse();
        var proximoFiltroChamado = new AtomicBoolean();

        filtro.doFilter(requisicao, resposta, (entrada, saida) -> proximoFiltroChamado.set(true));

        assertThat(resposta.getStatus()).isEqualTo(200);
        assertThat(proximoFiltroChamado).isTrue();
    }

    private void autenticar(String idEmpresa) {
        var construtor = Jwt.withTokenValue("token-teste")
                .header("alg", "RS256")
                .subject("usuario-teste")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300));
        if (idEmpresa != null) {
            construtor.claim(propriedades.claimEmpresa(), idEmpresa);
        }
        Jwt jwt = construtor.claims(claims -> claims.putAll(Map.of("token_use", "access"))).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }
}
