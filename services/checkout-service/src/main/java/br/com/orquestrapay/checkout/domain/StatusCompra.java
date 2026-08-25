package br.com.orquestrapay.checkout.domain;

public enum StatusCompra {
    RECEBIDA,
    ESTOQUE_RESERVADO,
    RISCO_APROVADO,
    AGUARDANDO_PAGAMENTO,
    PAGAMENTO_AUTORIZADO,
    COMPENSANDO,
    COMPENSADA,
    RECUSADA,
    CONCLUIDA
}
