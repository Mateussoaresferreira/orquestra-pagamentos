package br.com.orquestrapay.checkout.web;

import java.io.IOException;
import java.util.UUID;

import br.com.orquestrapay.checkout.service.LimitadorRequisicoes;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

@Component
public class FiltroLimiteRequisicoes extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(FiltroLimiteRequisicoes.class);

    private final LimitadorRequisicoes limitador;
    private final ObjectMapper json;
    private final MeterRegistry metricas;

    public FiltroLimiteRequisicoes(
            LimitadorRequisicoes limitador,
            ObjectMapper json,
            MeterRegistry metricas) {
        this.limitador = limitador;
        this.json = json;
        this.metricas = metricas;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest requisicao) {
        return !"POST".equals(requisicao.getMethod())
                || !"/api/v1/compras".equals(requisicao.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest requisicao,
            HttpServletResponse resposta,
            FilterChain cadeia) throws ServletException, IOException {
        String idEmpresa = requisicao.getHeader("X-Empresa-Id");
        if (!empresaValida(idEmpresa)) {
            cadeia.doFilter(requisicao, resposta);
            return;
        }
        try {
            var resultado = limitador.consumir(UUID.fromString(idEmpresa).toString());
            resposta.setHeader("X-RateLimit-Limit",
                    Integer.toString(limitador.propriedades().maximoPorJanela()));
            resposta.setHeader("X-RateLimit-Remaining", Long.toString(resultado.restante()));
            if (!resultado.permitido()) {
                metricas.counter("orquestrapay.requisicoes.limitadas").increment();
                resposta.setHeader("Retry-After",
                        Long.toString(Math.max(1, limitador.propriedades().janela().toSeconds())));
                escreverProblema(
                        resposta,
                        HttpStatus.TOO_MANY_REQUESTS.value(),
                        "limite-requisicoes-excedido",
                        "Aguarde antes de iniciar outra compra");
                return;
            }
        } catch (RedisConnectionFailureException excecao) {
            metricas.counter("orquestrapay.redis.indisponivel").increment();
            log.warn("Redis indisponivel durante a verificacao do limite de requisicoes", excecao);
            if (!limitador.propriedades().permitirSemRedis()) {
                escreverProblema(
                        resposta,
                        HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                        "limitador-indisponivel",
                        "Nao foi possivel validar o limite de requisicoes");
                return;
            }
        }
        cadeia.doFilter(requisicao, resposta);
    }

    private boolean empresaValida(String idEmpresa) {
        if (idEmpresa == null || idEmpresa.isBlank()) {
            return false;
        }
        try {
            UUID.fromString(idEmpresa);
            return true;
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
