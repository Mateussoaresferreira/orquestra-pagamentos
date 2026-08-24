package br.com.orquestrapay.platform.web;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

public class FiltroTamanhoRequisicao extends OncePerRequestFilter {

    private static final int STATUS_CORPO_MUITO_GRANDE = 413;

    private final long limite;

    public FiltroTamanhoRequisicao(PropriedadesWeb propriedades) {
        this.limite = propriedades.tamanhoMaximoCorpoBytes();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest requisicao) {
        return switch (requisicao.getMethod()) {
            case "POST", "PUT", "PATCH" -> false;
            default -> true;
        };
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest requisicao,
            HttpServletResponse resposta,
            FilterChain cadeia) throws ServletException, IOException {
        if (requisicao.getContentLengthLong() > limite) {
            escreverProblema(resposta);
            return;
        }

        var requisicaoLimitada = new RequisicaoLimitada(requisicao, limite);
        try {
            cadeia.doFilter(requisicaoLimitada, resposta);
        } catch (IOException | ServletException | RuntimeException excecao) {
            if (causadaPorLimite(excecao) && !resposta.isCommitted()) {
                resposta.reset();
                escreverProblema(resposta);
                return;
            }
            throw excecao;
        }
    }

    private boolean causadaPorLimite(Throwable excecao) {
        Throwable atual = excecao;
        while (atual != null) {
            if (atual instanceof CorpoMuitoGrandeException) {
                return true;
            }
            atual = atual.getCause();
        }
        return false;
    }

    private void escreverProblema(HttpServletResponse resposta) throws IOException {
        resposta.setStatus(STATUS_CORPO_MUITO_GRANDE);
        resposta.setCharacterEncoding(UTF_8.name());
        resposta.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        resposta.getWriter().write("""
                {"codigo":"corpo-requisicao-muito-grande",\
                "detalhe":"O corpo da requisicao ultrapassa o limite permitido",\
                "status":413}
                """);
    }

    private static final class RequisicaoLimitada extends HttpServletRequestWrapper {

        private final long limite;

        private RequisicaoLimitada(HttpServletRequest requisicao, long limite) {
            super(requisicao);
            this.limite = limite;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            return new EntradaLimitada(super.getInputStream(), limite);
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String codificacao = getCharacterEncoding();
            return new BufferedReader(new InputStreamReader(
                    getInputStream(),
                    codificacao == null ? UTF_8 : java.nio.charset.Charset.forName(codificacao)));
        }
    }

    private static final class EntradaLimitada extends ServletInputStream {

        private final ServletInputStream origem;
        private final long limite;
        private long lidos;

        private EntradaLimitada(ServletInputStream origem, long limite) {
            this.origem = origem;
            this.limite = limite;
        }

        @Override
        public int read() throws IOException {
            int valor = origem.read();
            if (valor != -1 && ++lidos > limite) {
                throw new CorpoMuitoGrandeException();
            }
            return valor;
        }

        @Override
        public int read(byte[] destino, int deslocamento, int tamanho) throws IOException {
            long restanteAteDetectarExcesso = Math.max(1, limite - lidos + 1);
            int quantidade = origem.read(
                    destino,
                    deslocamento,
                    (int) Math.min(tamanho, restanteAteDetectarExcesso));
            if (quantidade > 0 && (lidos += quantidade) > limite) {
                throw new CorpoMuitoGrandeException();
            }
            return quantidade;
        }

        @Override
        public boolean isFinished() {
            return origem.isFinished();
        }

        @Override
        public boolean isReady() {
            return origem.isReady();
        }

        @Override
        public void setReadListener(ReadListener ouvinte) {
            origem.setReadListener(ouvinte);
        }
    }

    private static final class CorpoMuitoGrandeException extends IOException {

        private CorpoMuitoGrandeException() {
            super("O corpo da requisicao ultrapassa o limite permitido");
        }
    }
}
