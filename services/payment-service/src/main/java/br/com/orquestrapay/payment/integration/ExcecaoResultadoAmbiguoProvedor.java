package br.com.orquestrapay.payment.integration;

public class ExcecaoResultadoAmbiguoProvedor extends RuntimeException {

    private final int statusHttp;

    public ExcecaoResultadoAmbiguoProvedor(int statusHttp) {
        super("O provedor respondeu sem confirmar se a requisicao foi processada (HTTP " + statusHttp + ")");
        this.statusHttp = statusHttp;
    }

    public int statusHttp() {
        return statusHttp;
    }
}
