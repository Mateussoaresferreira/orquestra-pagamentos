package br.com.orquestrapay.provider.security;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.security.MessageDigest;

import br.com.orquestrapay.provider.config.PropriedadesAutenticacaoProvedor;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

public class FiltroChaveApiProvedor extends OncePerRequestFilter {

    public static final String CABECALHO_CHAVE_API = "X-Provedor-Api-Key";
    public static final String CABECALHO_POLITICA_RECURSOS = "Cross-Origin-Resource-Policy";

    private final byte[] chaveEsperada;

    public FiltroChaveApiProvedor(PropriedadesAutenticacaoProvedor propriedades) {
        this.chaveEsperada = propriedades.chaveApi().getBytes(UTF_8);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest requisicao,
            HttpServletResponse resposta,
            FilterChain cadeia) throws ServletException, IOException {
        resposta.setHeader(CABECALHO_POLITICA_RECURSOS, "same-origin");
        if (rotaPublica(requisicao)) {
            cadeia.doFilter(requisicao, resposta);
            return;
        }

        String chaveRecebida = requisicao.getHeader(CABECALHO_CHAVE_API);
        boolean valida = chaveRecebida != null && MessageDigest.isEqual(
                chaveEsperada,
                chaveRecebida.getBytes(UTF_8));
        if (!valida) {
            resposta.sendError(
                    HttpStatus.UNAUTHORIZED.value(),
                    "Credencial do provedor ausente ou invalida");
            return;
        }
        cadeia.doFilter(requisicao, resposta);
    }

    private boolean rotaPublica(HttpServletRequest requisicao) {
        String caminho = requisicao.getRequestURI();
        return caminho.startsWith("/actuator/health")
                || caminho.equals("/actuator/prometheus");
    }
}
