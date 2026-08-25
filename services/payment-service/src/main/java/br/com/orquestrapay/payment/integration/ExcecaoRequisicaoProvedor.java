package br.com.orquestrapay.payment.integration;

public class ExcecaoRequisicaoProvedor extends RuntimeException {

    private final int statusHttp;

    public ExcecaoRequisicaoProvedor(int statusHttp) {
        super("O provedor rejeitou a requisicao com HTTP " + statusHttp);
        this.statusHttp = statusHttp;
    }

    public int statusHttp() {
        return statusHttp;
    }
}
