package br.com.orquestrapay.platform.security;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

public class FiltroEmpresaAutenticada extends OncePerRequestFilter {

    private final PropriedadesSeguranca propriedades;
    private final ObjectMapper json;

    public FiltroEmpresaAutenticada(PropriedadesSeguranca propriedades, ObjectMapper json) {
        this.propriedades = propriedades;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest requisicao,
            HttpServletResponse resposta,
            FilterChain cadeia) throws ServletException, IOException {
        if (!requisicao.getRequestURI().startsWith("/api/")) {
            cadeia.doFilter(requisicao, resposta);
            return;
        }

        var autenticacao = SecurityContextHolder.getContext().getAuthentication();
        if (autenticacao == null || !(autenticacao.getPrincipal() instanceof Jwt jwt)) {
            cadeia.doFilter(requisicao, resposta);
            return;
        }

        String empresaInformada = requisicao.getHeader("X-Empresa-Id");
        if (empresaInformada == null || empresaInformada.isBlank()) {
            escreverProblema(
                    resposta,
                    HttpServletResponse.SC_BAD_REQUEST,
                    "empresa-obrigatoria",
                    "Informe X-Empresa-Id para acessar a API");
            return;
        }

        String empresaAutorizada = resolverEmpresaAutorizada(jwt);
        if (!mesmaEmpresa(empresaAutorizada, empresaInformada)) {
            escreverProblema(
                    resposta,
                    HttpServletResponse.SC_FORBIDDEN,
                    "empresa-nao-autorizada",
                    "O token nao permite acessar os dados da empresa informada");
            return;
        }
        cadeia.doFilter(requisicao, resposta);
    }

    private String resolverEmpresaAutorizada(Jwt jwt) {
        String clienteToken = jwt.getClaimAsString("client_id");
        if (propriedades.clienteMaquinaId() != null
                && propriedades.clienteMaquinaId().equals(clienteToken)) {
            return propriedades.empresaClienteMaquina();
        }
        String empresaDoUsuario = jwt.getClaimAsString(propriedades.claimEmpresa());
        return empresaDoUsuario == null || empresaDoUsuario.isBlank() ? null : empresaDoUsuario;
    }

    private boolean mesmaEmpresa(String empresaAutorizada, String empresaInformada) {
        if (empresaAutorizada == null || empresaAutorizada.isBlank()) {
            return false;
        }
        try {
            return UUID.fromString(empresaAutorizada).equals(UUID.fromString(empresaInformada));
        } catch (IllegalArgumentException excecao) {
            return false;
        }
    }

    private void escreverProblema(
            HttpServletResponse resposta,
            int status,
            String codigo,
            String detalhe) throws IOException {
        resposta.setStatus(status);
        resposta.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(resposta.getOutputStream(), new Problema(codigo, detalhe, status));
    }

    private record Problema(String codigo, String detalhe, int status) {
    }
}
