package br.com.orquestrapay.platform.config;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest
@Import({
        ConfiguracaoSeguranca.class,
        TesteAutorizacaoRotas.ControladorRotas.class,
        TesteAutorizacaoRotas.ControladorInfraestrutura.class
})
@TestPropertySource(properties = {
        "orquestrapay.seguranca.habilitada=true",
        "orquestrapay.seguranca.emissor=https://identidade.exemplo",
        "orquestrapay.seguranca.clientes-id=cliente-api"
})
class TesteAutorizacaoRotas {

    private static final String EMPRESA = "65eeb170-5998-4c5d-9ee3-7588f6cceaa5";

    @Autowired
    private MockMvc http;

    @MockitoBean(name = "decodificadorJwt")
    private JwtDecoder decodificadorJwt;

    @Test
    void auditorPodeConsultarEntregaMasNaoReprocessar() throws Exception {
        http.perform(get("/api/v1/webhooks/entregas/{id}", UUID.randomUUID())
                        .header("X-Empresa-Id", EMPRESA)
                        .with(token("ROLE_AUDITOR")))
                .andExpect(status().isOk());

        http.perform(post("/api/v1/webhooks/entregas/{id}/reprocessar", UUID.randomUUID())
                        .header("X-Empresa-Id", EMPRESA)
                        .with(token("ROLE_AUDITOR")))
                .andExpect(status().isForbidden());
    }

    @Test
    void operadorPodeReprocessarEntrega() throws Exception {
        http.perform(post("/api/v1/webhooks/entregas/{id}/reprocessar", UUID.randomUUID())
                        .header("X-Empresa-Id", EMPRESA)
                        .with(token("ROLE_OPERADOR")))
                .andExpect(status().isOk());
    }

    @Test
    void auditorPodeConsultarDivergenciaMasNaoPodeAlteraLa() throws Exception {
        http.perform(get("/api/v1/conciliacoes/divergencias")
                        .header("X-Empresa-Id", EMPRESA)
                        .with(token("ROLE_AUDITOR")))
                .andExpect(status().isOk());

        http.perform(patch("/api/v1/conciliacoes/divergencias/{id}", UUID.randomUUID())
                        .header("X-Empresa-Id", EMPRESA)
                        .with(token("ROLE_AUDITOR")))
                .andExpect(status().isForbidden());
    }

    @Test
    void financeiroPodeAlterarDivergencia() throws Exception {
        http.perform(patch("/api/v1/conciliacoes/divergencias/{id}", UUID.randomUUID())
                        .header("X-Empresa-Id", EMPRESA)
                        .with(token("ROLE_FINANCEIRO")))
                .andExpect(status().isOk());
    }

    @Test
    void grupoAdministradorDoCognitoPodeOperarQuarentena() throws Exception {
        http.perform(get("/api/v1/admin/quarentena")
                        .header("X-Empresa-Id", EMPRESA)
                        .with(token("ROLE_ADMINISTRADOR")))
                .andExpect(status().isOk());
    }

    @Test
    void permiteAnonimamenteSomenteSaudeEPostDoWebhookDoProvedor() throws Exception {
        http.perform(get("/actuator/health"))
                .andExpect(status().isOk());

        http.perform(post("/api/v1/webhooks/provedores"))
                .andExpect(status().isOk());

        http.perform(get("/api/v1/webhooks/provedores"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protegeDocumentacaoERejeitaRotaNaoDeclaradaAteParaAdministrador() throws Exception {
        http.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isUnauthorized());

        http.perform(get("/swagger-ui/index.html")
                        .with(token("ROLE_DESENVOLVEDOR")))
                .andExpect(status().isOk());

        http.perform(get("/api/v1/interno/diagnostico")
                        .header("X-Empresa-Id", EMPRESA)
                        .with(token("ROLE_ADMINISTRADOR")))
                .andExpect(status().isForbidden());
    }

    @Test
    void exigeAdministradorAutenticadoParaQuarentena() throws Exception {
        http.perform(get("/api/v1/admin/quarentena"))
                .andExpect(status().isUnauthorized());

        http.perform(get("/api/v1/admin/quarentena")
                        .header("X-Empresa-Id", EMPRESA)
                        .with(token("ROLE_AUDITOR")))
                .andExpect(status().isForbidden());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor token(
            String autoridade) {
        return jwt()
                .jwt(token -> token
                        .claim("token_use", "access")
                        .claim("client_id", "cliente-api")
                        .claim("custom:empresa_id", EMPRESA))
                .authorities(new SimpleGrantedAuthority(autoridade));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableWebSecurity
    static class AplicacaoTeste {
    }

    @RestController
    @RequestMapping("/api/v1")
    static class ControladorRotas {

        @GetMapping("/webhooks/entregas/{id}")
        ResponseEntity<Void> consultarEntrega(@PathVariable UUID id) {
            return ResponseEntity.ok().build();
        }

        @PostMapping("/webhooks/entregas/{id}/reprocessar")
        ResponseEntity<Void> reprocessarEntrega(@PathVariable UUID id) {
            return ResponseEntity.ok().build();
        }

        @GetMapping("/conciliacoes/divergencias")
        ResponseEntity<Void> consultarDivergencias() {
            return ResponseEntity.ok().build();
        }

        @PatchMapping("/conciliacoes/divergencias/{id}")
        ResponseEntity<Void> alterarDivergencia(@PathVariable UUID id) {
            return ResponseEntity.ok().build();
        }

        @GetMapping("/admin/quarentena")
        ResponseEntity<Void> consultarQuarentena() {
            return ResponseEntity.ok().build();
        }

        @PostMapping("/webhooks/provedores")
        ResponseEntity<Void> receberWebhookProvedor() {
            return ResponseEntity.ok().build();
        }

        @GetMapping("/webhooks/provedores")
        ResponseEntity<Void> consultarWebhookProvedor() {
            return ResponseEntity.ok().build();
        }

        @GetMapping("/interno/diagnostico")
        ResponseEntity<Void> diagnosticoInterno() {
            return ResponseEntity.ok().build();
        }
    }

    @RestController
    static class ControladorInfraestrutura {

        @GetMapping("/actuator/health")
        ResponseEntity<Void> saude() {
            return ResponseEntity.ok().build();
        }

        @GetMapping("/swagger-ui/index.html")
        ResponseEntity<Void> documentacao() {
            return ResponseEntity.ok().build();
        }
    }
}
