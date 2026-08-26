package br.com.orquestrapay.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;

import br.com.orquestrapay.platform.config.ConfiguracaoWeb;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TesteFiltroCabecalhosSeguranca {

    private final WebApplicationContextRunner contextoWeb = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfiguracaoWeb.class));

    @Test
    void deveRestringirOCarregamentoDeRecursosAOrigemDaApi() throws Exception {
        var requisicao = new MockHttpServletRequest("GET", "/api/v1/compras");
        var resposta = new MockHttpServletResponse();
        var executouAplicacao = new AtomicBoolean();
        var filtro = new FiltroCabecalhosSeguranca();

        filtro.doFilter(
                requisicao,
                resposta,
                (pedido, retorno) -> executouAplicacao.set(true));

        assertThat(resposta.getHeader(FiltroCabecalhosSeguranca.CABECALHO_POLITICA_RECURSOS))
                .isEqualTo(FiltroCabecalhosSeguranca.POLITICA_MESMA_ORIGEM);
        assertThat(executouAplicacao).isTrue();
    }

    @Test
    void deveRegistrarOFiltroEmTodasAsRotasDaAplicacao() {
        contextoWeb.run(contexto -> {
            var bean = contexto.getBean("filtroCabecalhosSeguranca");

            assertThat(bean).isInstanceOf(FilterRegistrationBean.class);
            var registro = (FilterRegistrationBean<?>) bean;

            assertThat(registro.getFilter()).isInstanceOf(FiltroCabecalhosSeguranca.class);
            assertThat(registro.getUrlPatterns()).containsExactly("/*");
            assertThat(registro.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        });
    }
}
