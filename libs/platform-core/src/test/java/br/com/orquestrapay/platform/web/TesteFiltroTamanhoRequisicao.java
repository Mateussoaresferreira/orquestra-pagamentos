package br.com.orquestrapay.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TesteFiltroTamanhoRequisicao {

    @Test
    void deveRejeitarCorpoAcimaDoLimiteAntesDoControlador() throws Exception {
        var filtro = new FiltroTamanhoRequisicao(new PropriedadesWeb(1024));
        var requisicao = new MockHttpServletRequest("POST", "/api/v1/compras");
        requisicao.setContent(new byte[1025]);
        var resposta = new MockHttpServletResponse();
        var controladorChamado = new AtomicBoolean();

        filtro.doFilter(requisicao, resposta, (entrada, saida) -> controladorChamado.set(true));

        assertThat(resposta.getStatus()).isEqualTo(413);
        assertThat(resposta.getContentAsString()).contains("corpo-requisicao-muito-grande");
        assertThat(controladorChamado).isFalse();
    }

    @Test
    void devePermitirCorpoDentroDoLimite() throws Exception {
        var filtro = new FiltroTamanhoRequisicao(new PropriedadesWeb(1024));
        var requisicao = new MockHttpServletRequest("POST", "/api/v1/compras");
        requisicao.setContent(new byte[1024]);
        var resposta = new MockHttpServletResponse();
        var controladorChamado = new AtomicBoolean();

        filtro.doFilter(requisicao, resposta, (entrada, saida) -> {
            entrada.getInputStream().readAllBytes();
            controladorChamado.set(true);
        });

        assertThat(resposta.getStatus()).isEqualTo(200);
        assertThat(controladorChamado).isTrue();
    }
}
