package br.com.orquestrapay.platform.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest
@Import({
        ConfiguracaoSeguranca.class,
        TesteSegurancaLocalStateless.ControladorTeste.class
})
@TestPropertySource(properties = "orquestrapay.seguranca.habilitada=false")
class TesteSegurancaLocalStateless {

    @Autowired
    private MockMvc http;

    @Test
    void devePermitirApiSemCsrfENaoCriarSessao() throws Exception {
        var resultado = http.perform(post("/api/v1/teste"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(resultado.getRequest().getSession(false)).isNull();
    }

    @Test
    void deveManterCsrfAtivoForaDaSuperficieDaApi() throws Exception {
        http.perform(post("/formulario-interno"))
                .andExpect(status().isForbidden());
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableWebSecurity
    static class AplicacaoTeste {
    }

    @RestController
    static class ControladorTeste {

        @PostMapping("/api/v1/teste")
        ResponseEntity<Void> api() {
            return ResponseEntity.ok().build();
        }

        @PostMapping("/formulario-interno")
        ResponseEntity<Void> formulario() {
            return ResponseEntity.ok().build();
        }
    }
}
