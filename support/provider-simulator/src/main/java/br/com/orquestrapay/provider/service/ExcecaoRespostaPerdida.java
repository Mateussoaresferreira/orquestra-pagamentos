package br.com.orquestrapay.provider.service;

public class ExcecaoRespostaPerdida extends RuntimeException {

    public ExcecaoRespostaPerdida() {
        super("O provedor processou a requisicao, mas a resposta foi perdida");
    }
}
