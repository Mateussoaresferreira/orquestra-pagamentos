package br.com.orquestrapay.provider.service;

public class ExcecaoIndisponibilidadeConfirmada extends RuntimeException {

    public ExcecaoIndisponibilidadeConfirmada(String mensagem) {
        super(mensagem);
    }
}
