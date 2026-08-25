package br.com.orquestrapay.platform.web;

import java.net.URI;
import java.util.LinkedHashMap;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class TratadorExcecoes {

    @ExceptionHandler(ExcecaoNegocio.class)
    ProblemDetail tratarNegocio(ExcecaoNegocio excecao, HttpServletRequest requisicao) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(excecao.status(), excecao.getMessage());
        problema.setTitle("Regra de negocio nao atendida");
        problema.setType(URI.create("https://orquestrapay.dev/problemas/" + excecao.codigo()));
        problema.setInstance(URI.create(requisicao.getRequestURI()));
        problema.setProperty("codigo", excecao.codigo());
        return problema;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail tratarValidacao(MethodArgumentNotValidException excecao, HttpServletRequest requisicao) {
        var campos = new LinkedHashMap<String, String>();
        excecao.getBindingResult().getFieldErrors().forEach(erro ->
                campos.putIfAbsent(erro.getField(), erro.getDefaultMessage()));
        ProblemDetail problema = dadosInvalidos(requisicao);
        problema.setProperty("campos", campos);
        return problema;
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ProblemDetail tratarParametrosInvalidos(
            HandlerMethodValidationException excecao,
            HttpServletRequest requisicao) {
        return dadosInvalidos(requisicao);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ProblemDetail tratarFormatoInvalido(Exception excecao, HttpServletRequest requisicao) {
        ProblemDetail problema = dadosInvalidos(requisicao);
        problema.setDetail("O formato da requisicao e invalido");
        return problema;
    }

    private ProblemDetail dadosInvalidos(HttpServletRequest requisicao) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Existem campos invalidos na requisicao");
        problema.setTitle("Dados invalidos");
        problema.setType(URI.create("https://orquestrapay.dev/problemas/dados-invalidos"));
        problema.setInstance(URI.create(requisicao.getRequestURI()));
        problema.setProperty("codigo", "dados-invalidos");
        return problema;
    }
}
