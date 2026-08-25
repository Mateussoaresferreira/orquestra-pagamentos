package br.com.orquestrapay.payment.integration;

public class ExcecaoCotaProvedor extends RuntimeException {

    private final long tentarNovamenteEmMillis;

    public ExcecaoCotaProvedor(String provedor, long tentarNovamenteEmMillis) {
        super("A cota temporaria do provedor " + provedor + " foi atingida");
        this.tentarNovamenteEmMillis = tentarNovamenteEmMillis;
    }

    public long tentarNovamenteEmMillis() {
        return tentarNovamenteEmMillis;
    }
}
