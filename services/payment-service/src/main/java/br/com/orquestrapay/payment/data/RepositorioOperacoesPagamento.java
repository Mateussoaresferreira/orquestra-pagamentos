package br.com.orquestrapay.payment.data;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import br.com.orquestrapay.contracts.MetodoPagamento;
import br.com.orquestrapay.payment.domain.OperacaoPagamento;
import br.com.orquestrapay.payment.domain.StatusOperacaoPagamento;
import br.com.orquestrapay.payment.domain.TipoOperacaoPagamento;
import br.com.orquestrapay.platform.data.DatasSql;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RepositorioOperacoesPagamento {

    private final JdbcClient banco;

    public RepositorioOperacoesPagamento(JdbcClient banco) {
        this.banco = banco;
    }

    public void adicionar(UUID idPagamento, TipoOperacaoPagamento tipo, Instant agora) {
        banco.sql("""
                        INSERT INTO operacao_pagamento (
                            id_operacao, id_pagamento, tipo, status,
                            proxima_tentativa_em, criada_em, atualizada_em
                        ) VALUES (
                            :idOperacao, :idPagamento, :tipo, 'PENDENTE',
                            :agora, :agora, :agora
                        )
                        ON CONFLICT (id_pagamento, tipo) DO UPDATE
                           SET status = 'PENDENTE',
                               proxima_tentativa_em = EXCLUDED.proxima_tentativa_em,
                               bloqueado_ate = NULL,
                               token_bloqueio = NULL,
                               ultimo_erro = NULL,
                               atualizada_em = EXCLUDED.atualizada_em
                         WHERE operacao_pagamento.status = 'FALHA_DEFINITIVA'
                        """)
                .param("idOperacao", UUID.randomUUID())
                .param("idPagamento", idPagamento)
                .param("tipo", tipo.name())
                .param("agora", DatasSql.gravar(agora))
                .update();
    }

    public List<OperacaoPagamento> reivindicar(
            int tamanhoLote,
            Instant agora,
            Instant bloqueadoAte,
            UUID tokenBloqueio) {
        List<UUID> ids = banco.sql("""
                        SELECT id_operacao
                          FROM operacao_pagamento
                         WHERE (
                                status = 'PENDENTE'
                                OR (status = 'PROCESSANDO' AND bloqueado_ate <= :agora)
                               )
                           AND proxima_tentativa_em <= :agora
                         ORDER BY proxima_tentativa_em, criada_em
                         FOR UPDATE SKIP LOCKED
                         LIMIT :limite
                        """)
                .param("agora", DatasSql.gravar(agora))
                .param("limite", tamanhoLote)
                .query(UUID.class)
                .list();
        if (ids.isEmpty()) {
            return List.of();
        }

        banco.sql("""
                        UPDATE operacao_pagamento
                           SET status = 'PROCESSANDO',
                               tentativas = tentativas + 1,
                               bloqueado_ate = :bloqueadoAte,
                               token_bloqueio = :tokenBloqueio,
                               atualizada_em = :agora
                         WHERE id_operacao IN (:ids)
                        """)
                .param("bloqueadoAte", DatasSql.gravar(bloqueadoAte))
                .param("tokenBloqueio", tokenBloqueio)
                .param("agora", DatasSql.gravar(agora))
                .param("ids", ids)
                .update();

        return banco.sql("""
                        SELECT o.id_operacao, o.tipo, o.tentativas, o.token_bloqueio,
                               p.id_pagamento, p.id_empresa, p.id_compra,
                               p.metodo_pagamento, p.valor, p.moeda,
                               p.token_protegido, p.parcelas, p.provedor
                          FROM operacao_pagamento o
                          JOIN pagamento p ON p.id_pagamento = o.id_pagamento
                         WHERE o.id_operacao IN (:ids)
                           AND o.token_bloqueio = :tokenBloqueio
                         ORDER BY o.criada_em
                        """)
                .param("ids", ids)
                .param("tokenBloqueio", tokenBloqueio)
                .query((resultado, linha) -> new OperacaoPagamento(
                        resultado.getObject("id_operacao", UUID.class),
                        resultado.getObject("id_pagamento", UUID.class),
                        resultado.getObject("id_empresa", UUID.class),
                        resultado.getObject("id_compra", UUID.class),
                        TipoOperacaoPagamento.valueOf(resultado.getString("tipo")),
                        MetodoPagamento.valueOf(resultado.getString("metodo_pagamento")),
                        resultado.getBigDecimal("valor"),
                        resultado.getString("moeda"),
                        resultado.getString("token_protegido"),
                        resultado.getInt("parcelas"),
                        resultado.getString("provedor"),
                        resultado.getInt("tentativas"),
                        resultado.getObject("token_bloqueio", UUID.class)))
                .list();
    }

    public boolean concluir(OperacaoPagamento operacao, Instant agora) {
        return atualizarStatus(
                operacao,
                StatusOperacaoPagamento.CONCLUIDA,
                agora,
                agora,
                null) == 1;
    }

    public boolean reagendar(
            OperacaoPagamento operacao,
            Instant proximaTentativa,
            String erro,
            Instant agora) {
        return atualizarStatus(
                operacao,
                StatusOperacaoPagamento.PENDENTE,
                proximaTentativa,
                agora,
                erro) == 1;
    }

    public boolean marcarFalhaDefinitiva(
            OperacaoPagamento operacao,
            String erro,
            Instant agora) {
        return atualizarStatus(
                operacao,
                StatusOperacaoPagamento.FALHA_DEFINITIVA,
                agora,
                agora,
                erro) == 1;
    }

    public boolean reprocessar(UUID idOperacao, Instant agora) {
        return banco.sql("""
                        UPDATE operacao_pagamento
                           SET status = 'PENDENTE',
                               tentativas = 0,
                               proxima_tentativa_em = :agora,
                               bloqueado_ate = NULL,
                               token_bloqueio = NULL,
                               ultimo_erro = NULL,
                               atualizada_em = :agora
                         WHERE id_operacao = :idOperacao
                           AND status = 'FALHA_DEFINITIVA'
                        """)
                .param("idOperacao", idOperacao)
                .param("agora", DatasSql.gravar(agora))
                .update() == 1;
    }

    private int atualizarStatus(
            OperacaoPagamento operacao,
            StatusOperacaoPagamento status,
            Instant proximaTentativa,
            Instant agora,
            String erro) {
        return banco.sql("""
                        UPDATE operacao_pagamento
                           SET status = :status,
                               proxima_tentativa_em = :proximaTentativa,
                               bloqueado_ate = NULL,
                               token_bloqueio = NULL,
                               ultimo_erro = :erro,
                               atualizada_em = :agora
                         WHERE id_operacao = :idOperacao
                           AND status = 'PROCESSANDO'
                           AND token_bloqueio = :tokenBloqueio
                        """)
                .param("status", status.name())
                .param("proximaTentativa", DatasSql.gravar(proximaTentativa))
                .param("erro", limitar(erro, 2_000))
                .param("agora", DatasSql.gravar(agora))
                .param("idOperacao", operacao.idOperacao())
                .param("tokenBloqueio", operacao.tokenBloqueio())
                .update();
    }

    private String limitar(String texto, int limite) {
        if (texto == null || texto.length() <= limite) {
            return texto;
        }
        return texto.substring(0, limite);
    }
}
