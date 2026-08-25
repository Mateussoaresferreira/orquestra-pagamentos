package br.com.orquestrapay.ledger.service;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

import br.com.orquestrapay.ledger.api.LiquidacaoParcela;
import br.com.orquestrapay.ledger.api.ParcelaRecebivel;
import br.com.orquestrapay.ledger.data.RepositorioRazao;
import br.com.orquestrapay.platform.web.ExcecaoNegocio;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ServicoParcelas {

    private final RepositorioRazao repositorio;
    private final Clock relogio;

    public ServicoParcelas(RepositorioRazao repositorio, Clock relogio) {
        this.repositorio = repositorio;
        this.relogio = relogio;
    }

    @Transactional
    public ParcelaRecebivel liquidar(
            UUID idEmpresa,
            UUID idCompra,
            int numero,
            LiquidacaoParcela requisicao) {
        ParcelaRecebivel parcela = repositorio.bloquearParcela(idEmpresa, idCompra, numero)
                .orElseThrow(() -> new ExcecaoNegocio(
                        HttpStatus.NOT_FOUND,
                        "parcela-nao-encontrada",
                        "Parcela nao encontrada para esta empresa e compra"));

        if ("LIQUIDADA".equals(parcela.status())) {
            if (Objects.equals(parcela.referenciaLiquidacao(), requisicao.referencia())) {
                return parcela;
            }
            throw new ExcecaoNegocio(
                    HttpStatus.CONFLICT,
                    "liquidacao-conflitante",
                    "A parcela ja foi liquidada com outra referencia");
        }
        if (!"AGENDADA".equals(parcela.status())) {
            throw new ExcecaoNegocio(
                    HttpStatus.CONFLICT,
                    "parcela-indisponivel",
                    "A parcela nao esta disponivel para liquidacao");
        }

        var agora = relogio.instant();
        repositorio.liquidarParcela(parcela.idParcela(), requisicao.referencia(), agora);
        return new ParcelaRecebivel(
                parcela.idParcela(),
                parcela.numero(),
                parcela.totalParcelas(),
                parcela.valor(),
                parcela.vencimento(),
                "LIQUIDADA",
                requisicao.referencia(),
                parcela.criadaEm(),
                agora);
    }
}
