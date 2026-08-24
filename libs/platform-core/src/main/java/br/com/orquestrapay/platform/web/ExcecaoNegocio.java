package br.com.orquestrapay.platform.web;

import org.springframework.http.HttpStatusCode;

public class ExcecaoNegocio extends RuntimeException {

    private final HttpStatusCode status;
    private final String codigo;

    public ExcecaoNegocio(HttpStatusCode status, String codigo, String mensagem) {
        super(mensagem);
        this.status = status;
        this.codigo = codigo;
    }

    public HttpStatusCode status() {
        return status;
    }

    public String codigo() {
        return codigo;
    }
}
