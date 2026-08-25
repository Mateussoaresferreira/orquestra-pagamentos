package br.com.orquestrapay.payment.domain;

public enum StatusPagamento {
    PENDENTE,
    PROCESSANDO,
    AGUARDANDO_CONFIRMACAO,
    AUTORIZADO,
    RECUSADO,
    ESTORNO_PENDENTE,
    ESTORNANDO,
    FALHA_TECNICA,
    EXPIRADO,
    ESTORNADO
}
