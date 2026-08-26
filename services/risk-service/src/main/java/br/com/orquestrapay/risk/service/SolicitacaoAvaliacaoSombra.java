package br.com.orquestrapay.risk.service;

import java.util.UUID;

import br.com.orquestrapay.risk.domain.ContextoRisco;
import br.com.orquestrapay.risk.domain.ResultadoAvaliacaoRisco;

public record SolicitacaoAvaliacaoSombra(
        UUID idEmpresa,
        UUID idCompra,
        ContextoRisco contexto,
        ResultadoAvaliacaoRisco champion) {
}
