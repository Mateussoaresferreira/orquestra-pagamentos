package br.com.orquestrapay.platform.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

class TesteTratadorExcecoes {

    private MockMvc http;

    @BeforeEach
    void preparar() {
        var validador = new LocalValidatorFactoryBean();
        validador.afterPropertiesSet();
        http = MockMvcBuilders.standaloneSetup(new ControladorTeste())
                .setControllerAdvice(new TratadorExcecoes())
                .setValidator(validador)
                .build();
    }

    @Test
    void deveSanitizarJsonMalformado() throws Exception {
        http.perform(post("/teste")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("dados-invalidos"))
                .andExpect(jsonPath("$.detail").value("O formato da requisicao e invalido"));
    }

    @Test
    void deveSanitizarTipoDeParametroInvalido() throws Exception {
        http.perform(get("/teste/{id}", "nao-e-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("dados-invalidos"))
                .andExpect(jsonPath("$.detail").value("O formato da requisicao e invalido"));
    }

    @Test
    void devePadronizarRestricaoEmParametroDoMetodo() throws Exception {
        http.perform(get("/teste").param("limite", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("dados-invalidos"))
                .andExpect(jsonPath("$.detail").value("Existem campos invalidos na requisicao"));
    }

    @Test
    void devePreservarCamposDaValidacaoDoCorpo() throws Exception {
        http.perform(post("/teste")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("dados-invalidos"))
                .andExpect(jsonPath("$.campos.nome").exists());
    }

    @RestController
    static class ControladorTeste {

        @PostMapping("/teste")
        void criar(@Valid @RequestBody Entrada entrada) {
        }

        @GetMapping("/teste/{id}")
        void buscar(@PathVariable UUID id) {
        }

        @GetMapping("/teste")
        void listar(@RequestParam @Min(1) int limite) {
        }
    }

    record Entrada(@NotBlank String nome) {
    }
}
