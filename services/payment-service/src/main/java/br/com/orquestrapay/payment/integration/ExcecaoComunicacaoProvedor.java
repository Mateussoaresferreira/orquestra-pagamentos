package br.com.orquestrapay.payment.integration;

public class ExcecaoComunicacaoProvedor extends RuntimeException {

    private final String provedor;
    private final String operacao;
    private final NaturezaFalhaProvedor natureza;

    public ExcecaoComunicacaoProvedor(
            String provedor,
            String operacao,
            RuntimeException causa) {
        this(provedor, operacao, NaturezaFalhaProvedor.RESULTADO_AMBIGUO, causa);
    }

    public ExcecaoComunicacaoProvedor(
            String provedor,
            String operacao,
            NaturezaFalhaProvedor natureza,
            RuntimeException causa) {
        super("Falha tecnica ao executar " + operacao + " no provedor " + provedor, causa);
        this.provedor = provedor;
        this.operacao = operacao;
        this.natureza = natureza;
    }

    public String provedor() {
        return provedor;
    }

    public String operacao() {
        return operacao;
    }

    public NaturezaFalhaProvedor natureza() {
        return natureza;
    }

    public boolean permiteFallback() {
        return natureza == NaturezaFalhaProvedor.SEGURA_PARA_FALLBACK;
    }
}
