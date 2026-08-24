package br.com.orquestrapay.contracts;

public final class TiposEventos {

    public static final String RESERVAR_ESTOQUE = "RESERVAR_ESTOQUE";
    public static final String ESTOQUE_RESERVADO = "ESTOQUE_RESERVADO";
    public static final String ESTOQUE_RECUSADO = "ESTOQUE_RECUSADO";
    public static final String LIBERAR_ESTOQUE = "LIBERAR_ESTOQUE";
    public static final String ESTOQUE_LIBERADO = "ESTOQUE_LIBERADO";

    public static final String ANALISAR_RISCO = "ANALISAR_RISCO";
    public static final String RISCO_APROVADO = "RISCO_APROVADO";
    public static final String RISCO_REPROVADO = "RISCO_REPROVADO";

    public static final String AUTORIZAR_PAGAMENTO = "AUTORIZAR_PAGAMENTO";
    public static final String PAGAMENTO_AUTORIZADO = "PAGAMENTO_AUTORIZADO";
    public static final String PAGAMENTO_RECUSADO = "PAGAMENTO_RECUSADO";
    public static final String ESTORNAR_PAGAMENTO = "ESTORNAR_PAGAMENTO";
    public static final String PAGAMENTO_ESTORNADO = "PAGAMENTO_ESTORNADO";

    public static final String REGISTRAR_LANCAMENTOS = "REGISTRAR_LANCAMENTOS";
    public static final String LANCAMENTOS_REGISTRADOS = "LANCAMENTOS_REGISTRADOS";
    public static final String LANCAMENTOS_RECUSADOS = "LANCAMENTOS_RECUSADOS";

    public static final String COMPRA_CONCLUIDA = "COMPRA_CONCLUIDA";
    public static final String COMPRA_RECUSADA = "COMPRA_RECUSADA";
    public static final String COMPRA_COMPENSADA = "COMPRA_COMPENSADA";

    private TiposEventos() {
    }
}
