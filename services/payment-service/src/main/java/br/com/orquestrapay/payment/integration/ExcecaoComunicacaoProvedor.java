package br.com.orquestrapay.payment.integration;

public class ExcecaoComunicacaoProvedor extends RuntimeException {

    private final String provedor;
    private final String operacao;

    public ExcecaoComunicacaoProvedor(
            String provedor,
            String operacao,
            RuntimeException causa) {
        super("Falha tecnica ao executar " + operacao + " no provedor " + provedor, causa);
        this.provedor = provedor;
        this.operacao = operacao;
    }

    public String provedor() {
        return provedor;
    }

    public String operacao() {
        return operacao;
    }
}
