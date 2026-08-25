package br.com.orquestrapay.payment.integration;

public class ExcecaoIndisponibilidadeConfirmadaProvedor extends RuntimeException {

    private final int statusHttp;

    public ExcecaoIndisponibilidadeConfirmadaProvedor(int statusHttp) {
        super("O provedor confirmou que a requisicao nao foi processada (HTTP " + statusHttp + ")");
        this.statusHttp = statusHttp;
    }

    public int statusHttp() {
        return statusHttp;
    }
}
