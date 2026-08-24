package br.com.orquestrapay.checkout.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.concurrent.atomic.AtomicBoolean;

import br.com.orquestrapay.checkout.service.LimitadorRequisicoes;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

class TesteFiltroLimiteRequisicoes {

    @Test
    void naoDeveCriarChaveRedisComEmpresaInvalida() throws Exception {
        var limitador = mock(LimitadorRequisicoes.class);
        var filtro = new FiltroLimiteRequisicoes(
                limitador,
                new ObjectMapper(),
                new SimpleMeterRegistry());
        var requisicao = new MockHttpServletRequest("POST", "/api/v1/compras");
        requisicao.addHeader("X-Empresa-Id", "empresa'; DROP TABLE compra; --");
        var resposta = new MockHttpServletResponse();
        var proximoFiltroChamado = new AtomicBoolean();

        filtro.doFilter(
                requisicao,
                resposta,
                (entrada, saida) -> proximoFiltroChamado.set(true));

        assertThat(proximoFiltroChamado).isTrue();
        verifyNoInteractions(limitador);
    }
}
