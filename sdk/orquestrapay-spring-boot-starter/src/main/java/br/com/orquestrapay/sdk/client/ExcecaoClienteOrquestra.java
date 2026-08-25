package br.com.orquestrapay.sdk.client;

public class ExcecaoClienteOrquestra extends RuntimeException {

    private final int statusHttp;

    public ExcecaoClienteOrquestra(int statusHttp, String mensagem, Throwable causa) {
        super(mensagem, causa);
        this.statusHttp = statusHttp;
    }

    public int statusHttp() {
        return statusHttp;
    }
}
