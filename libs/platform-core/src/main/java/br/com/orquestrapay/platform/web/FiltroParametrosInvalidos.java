package br.com.orquestrapay.platform.web;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

public class FiltroParametrosInvalidos extends OncePerRequestFilter {

    private static final String EXCECAO_PARAMETRO_INVALIDO_TOMCAT =
            "org.apache.tomcat.util.http.InvalidParameterException";

    @Override
    protected void doFilterInternal(
            HttpServletRequest requisicao,
            HttpServletResponse resposta,
            FilterChain cadeia) throws ServletException, IOException {
        try {
            cadeia.doFilter(requisicao, resposta);
        } catch (IOException | ServletException | RuntimeException excecao) {
            if (causadaPorParametroInvalido(excecao) && !resposta.isCommitted()) {
                resposta.reset();
                escreverProblema(resposta);
                return;
            }
            throw excecao;
        }
    }

    private boolean causadaPorParametroInvalido(Throwable excecao) {
        Throwable atual = excecao;
        while (atual != null) {
            if (EXCECAO_PARAMETRO_INVALIDO_TOMCAT.equals(atual.getClass().getName())) {
                return true;
            }
            atual = atual.getCause();
        }
        return false;
    }

    private void escreverProblema(HttpServletResponse resposta) throws IOException {
        resposta.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        resposta.setCharacterEncoding(UTF_8.name());
        resposta.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        resposta.getWriter().write("""
                {"codigo":"parametros-invalidos",\
                "detalhe":"A requisicao possui parametros malformados",\
                "status":400}
                """);
    }
}
