package br.com.orquestrapay.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.apache.tomcat.util.http.InvalidParameterException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TesteFiltroParametrosInvalidos {

    @Test
    void deveConverterFalhaDoParserDeParametrosEmRespostaControlada() throws Exception {
        var filtro = new FiltroParametrosInvalidos();
        var requisicao = new MockHttpServletRequest("GET", "/api/v1/compras");
        var resposta = new MockHttpServletResponse();

        filtro.doFilter(requisicao, resposta, (entrada, saida) -> {
            throw new InvalidParameterException("Parametro malformado");
        });

        assertThat(resposta.getStatus()).isEqualTo(400);
        assertThat(resposta.getContentType()).startsWith("application/problem+json");
        assertThat(resposta.getContentAsString()).contains("parametros-invalidos");
    }

    @Test
    void naoDeveOcultarExcecaoQueNaoVeioDoParserDeParametros() {
        var filtro = new FiltroParametrosInvalidos();
        var requisicao = new MockHttpServletRequest("GET", "/api/v1/compras");
        var resposta = new MockHttpServletResponse();

        assertThatThrownBy(() -> filtro.doFilter(
                requisicao,
                resposta,
                (entrada, saida) -> {
                    throw new IllegalStateException("Falha real");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Falha real");
    }
}
