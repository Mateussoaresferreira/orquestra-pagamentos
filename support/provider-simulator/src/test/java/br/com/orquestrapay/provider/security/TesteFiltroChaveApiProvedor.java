package br.com.orquestrapay.provider.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;

import br.com.orquestrapay.provider.config.PropriedadesAutenticacaoProvedor;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TesteFiltroChaveApiProvedor {

    private static final String CHAVE = "chave-api-provedor-para-testes";
    private final FiltroChaveApiProvedor filtro = new FiltroChaveApiProvedor(
            new PropriedadesAutenticacaoProvedor(CHAVE));

    @Test
    void deveNegarOperacaoSemCredencial() throws Exception {
        var requisicao = new MockHttpServletRequest("POST", "/api/v1/autorizacoes");
        var resposta = new MockHttpServletResponse();
        var executouAplicacao = new AtomicBoolean();

        filtro.doFilter(requisicao, resposta, cadeia(executouAplicacao));

        assertThat(resposta.getStatus()).isEqualTo(401);
        assertThat(resposta.getHeader(FiltroChaveApiProvedor.CABECALHO_POLITICA_RECURSOS))
                .isEqualTo("same-origin");
        assertThat(executouAplicacao).isFalse();
    }

    @Test
    void devePermitirOperacaoComCredencialCorreta() throws Exception {
        var requisicao = new MockHttpServletRequest("POST", "/api/v1/autorizacoes");
        requisicao.addHeader(FiltroChaveApiProvedor.CABECALHO_CHAVE_API, CHAVE);
        var resposta = new MockHttpServletResponse();
        var executouAplicacao = new AtomicBoolean();

        filtro.doFilter(requisicao, resposta, cadeia(executouAplicacao));

        assertThat(resposta.getStatus()).isEqualTo(200);
        assertThat(executouAplicacao).isTrue();
    }

    @Test
    void deveManterHealthcheckPublico() throws Exception {
        var requisicao = new MockHttpServletRequest("GET", "/actuator/health/readiness");
        var resposta = new MockHttpServletResponse();
        var executouAplicacao = new AtomicBoolean();

        filtro.doFilter(requisicao, resposta, cadeia(executouAplicacao));

        assertThat(resposta.getHeader(FiltroChaveApiProvedor.CABECALHO_POLITICA_RECURSOS))
                .isEqualTo("same-origin");
        assertThat(executouAplicacao).isTrue();
    }

    @Test
    void devePermitirColetaInternaDeMetricas() throws Exception {
        var requisicao = new MockHttpServletRequest("GET", "/actuator/prometheus");
        var resposta = new MockHttpServletResponse();
        var executouAplicacao = new AtomicBoolean();

        filtro.doFilter(requisicao, resposta, cadeia(executouAplicacao));

        assertThat(executouAplicacao).isTrue();
    }

    @Test
    void deveProtegerDemaisRotasDoActuator() throws Exception {
        var requisicao = new MockHttpServletRequest("GET", "/actuator/env");
        var resposta = new MockHttpServletResponse();
        var executouAplicacao = new AtomicBoolean();

        filtro.doFilter(requisicao, resposta, cadeia(executouAplicacao));

        assertThat(resposta.getStatus()).isEqualTo(401);
        assertThat(executouAplicacao).isFalse();
    }

    @Test
    void deveProtegerDocumentacaoInterna() throws Exception {
        var requisicao = new MockHttpServletRequest("GET", "/v3/api-docs");
        var resposta = new MockHttpServletResponse();
        var executouAplicacao = new AtomicBoolean();

        filtro.doFilter(requisicao, resposta, cadeia(executouAplicacao));

        assertThat(resposta.getStatus()).isEqualTo(401);
        assertThat(executouAplicacao).isFalse();
    }

    private FilterChain cadeia(AtomicBoolean executouAplicacao) {
        return (requisicao, resposta) -> executouAplicacao.set(true);
    }
}
