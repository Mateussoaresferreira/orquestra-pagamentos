package br.com.orquestrapay.platform.web;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

public class FiltroCabecalhosSeguranca extends OncePerRequestFilter {

    public static final String CABECALHO_POLITICA_RECURSOS = "Cross-Origin-Resource-Policy";
    public static final String POLITICA_MESMA_ORIGEM = "same-origin";

    @Override
    protected void doFilterInternal(
            HttpServletRequest requisicao,
            HttpServletResponse resposta,
            FilterChain cadeia) throws ServletException, IOException {
        resposta.setHeader(CABECALHO_POLITICA_RECURSOS, POLITICA_MESMA_ORIGEM);
        cadeia.doFilter(requisicao, resposta);
    }
}
