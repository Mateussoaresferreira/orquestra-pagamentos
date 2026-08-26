package br.com.orquestrapay.risk.domain;

public enum ClassificacaoComparacaoRisco {
    DECISAO_CONCORDANTE,
    CHALLENGER_MAIS_RESTRITIVO,
    CHALLENGER_MAIS_PERMISSIVO;

    public static ClassificacaoComparacaoRisco comparar(
            ResultadoAvaliacaoRisco champion,
            ResultadoAvaliacaoRisco challenger) {
        if (champion.aprovada() == challenger.aprovada()) {
            return DECISAO_CONCORDANTE;
        }
        return champion.aprovada()
                ? CHALLENGER_MAIS_RESTRITIVO
                : CHALLENGER_MAIS_PERMISSIVO;
    }
}
