package br.com.orquestrapay.payment.integration;

public class ExcecaoLimitadorProvedor extends RuntimeException {

    public ExcecaoLimitadorProvedor(String provedor, RuntimeException causa) {
        super("Nao foi possivel validar a cota do provedor " + provedor, causa);
    }
}
