package br.com.orquestrapay.checkout.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.UUID;

import br.com.orquestrapay.checkout.config.PropriedadesLimiteRequisicoes;
import br.com.orquestrapay.checkout.service.ControladorAdmissaoLocal;
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
        var metricas = new SimpleMeterRegistry();
        var filtro = new FiltroLimiteRequisicoes(
                limitador,
                new ControladorAdmissaoLocal(
                        new PropriedadesLimiteRequisicoes(true, 60, null, 300, null, false, 1),
                        metricas),
                new ObjectMapper(),
                metricas);
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

    @Test
    void deveResponder429QuandoAReplicaEstiverSaturada() throws Exception {
        var limitador = mock(LimitadorRequisicoes.class);
        var metricas = new SimpleMeterRegistry();
        var admissao = new ControladorAdmissaoLocal(propriedades(1), metricas);
        var vagaOcupada = admissao.tentarAdmitir().orElseThrow();
        var filtro = new FiltroLimiteRequisicoes(limitador, admissao, new ObjectMapper(), metricas);
        var requisicao = requisicaoValida();
        var resposta = new MockHttpServletResponse();
        var proximoFiltroChamado = new AtomicBoolean();

        filtro.doFilter(requisicao, resposta, (entrada, saida) -> proximoFiltroChamado.set(true));

        assertThat(proximoFiltroChamado).isFalse();
        assertThat(resposta.getStatus()).isEqualTo(429);
        assertThat(resposta.getHeader("Retry-After")).isEqualTo("1");
        assertThat(resposta.getContentAsString()).contains("capacidade-temporariamente-esgotada");
        verifyNoInteractions(limitador);
        vagaOcupada.close();
    }

    @Test
    void deveResponder429ComOsLimitesDistribuidos() throws Exception {
        var limitador = mock(LimitadorRequisicoes.class);
        var propriedades = propriedades(2);
        when(limitador.propriedades()).thenReturn(propriedades);
        when(limitador.consumir(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new LimitadorRequisicoes.ResultadoLimite(false, 0, 7, 1_500));
        var metricas = new SimpleMeterRegistry();
        var admissao = new ControladorAdmissaoLocal(propriedades, metricas);
        var filtro = new FiltroLimiteRequisicoes(limitador, admissao, new ObjectMapper(), metricas);
        var resposta = new MockHttpServletResponse();

        filtro.doFilter(requisicaoValida(), resposta, (entrada, saida) -> {
            throw new AssertionError("A requisicao limitada nao pode chegar ao controlador");
        });

        assertThat(resposta.getStatus()).isEqualTo(429);
        assertThat(resposta.getHeader("Retry-After")).isEqualTo("2");
        assertThat(resposta.getHeader("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(resposta.getHeader("X-RateLimit-Global-Remaining")).isEqualTo("7");
        assertThat(admissao.emProcessamento()).isZero();
    }

    @Test
    void deveLiberarAVagaDepoisDeEncaminharARequisicao() throws Exception {
        var limitador = mock(LimitadorRequisicoes.class);
        var propriedades = propriedades(1);
        when(limitador.propriedades()).thenReturn(propriedades);
        when(limitador.consumir(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new LimitadorRequisicoes.ResultadoLimite(true, 59, 299, 0));
        var metricas = new SimpleMeterRegistry();
        var admissao = new ControladorAdmissaoLocal(propriedades, metricas);
        var filtro = new FiltroLimiteRequisicoes(limitador, admissao, new ObjectMapper(), metricas);
        var proximoFiltroChamado = new AtomicBoolean();

        filtro.doFilter(
                requisicaoValida(),
                new MockHttpServletResponse(),
                (entrada, saida) -> proximoFiltroChamado.set(true));

        assertThat(proximoFiltroChamado).isTrue();
        assertThat(admissao.emProcessamento()).isZero();
        try (var novaVaga = admissao.tentarAdmitir().orElseThrow()) {
            assertThat(admissao.emProcessamento()).isEqualTo(1);
        }
    }

    private MockHttpServletRequest requisicaoValida() {
        var requisicao = new MockHttpServletRequest("POST", "/api/v1/compras");
        requisicao.addHeader("X-Empresa-Id", UUID.randomUUID().toString());
        return requisicao;
    }

    private PropriedadesLimiteRequisicoes propriedades(int maximoEmProcessamento) {
        return new PropriedadesLimiteRequisicoes(
                true,
                60,
                null,
                300,
                null,
                false,
                maximoEmProcessamento);
    }
}
