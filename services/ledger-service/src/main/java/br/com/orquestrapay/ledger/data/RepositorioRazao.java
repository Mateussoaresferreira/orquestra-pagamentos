package br.com.orquestrapay.ledger.data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import br.com.orquestrapay.ledger.api.Lancamento;
import br.com.orquestrapay.ledger.api.RespostaTransacaoContabil;
import br.com.orquestrapay.ledger.domain.NaturezaLancamento;
import br.com.orquestrapay.platform.data.DatasSql;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RepositorioRazao {

    private final JdbcClient banco;

    public RepositorioRazao(JdbcClient banco) {
        this.banco = banco;
    }

    public boolean existePorCompra(UUID idEmpresa, UUID idCompra) {
        return banco.sql("""
                        SELECT COUNT(*) FROM transacao_contabil
                         WHERE id_empresa = :idEmpresa AND id_compra = :idCompra
                        """)
                .param("idEmpresa", idEmpresa)
                .param("idCompra", idCompra)
                .query(Integer.class)
                .single() > 0;
    }

    public void abrir(
            UUID idTransacao,
            UUID idEmpresa,
            UUID idCompra,
            UUID idPagamento,
            BigDecimal valor,
            String moeda,
            Instant agora) {
        banco.sql("""
                        INSERT INTO transacao_contabil (
                            id_transacao, id_empresa, id_compra, id_pagamento,
                            valor, moeda, status, criada_em
                        ) VALUES (
                            :idTransacao, :idEmpresa, :idCompra, :idPagamento,
                            :valor, :moeda, 'ABERTA', :agora
                        )
                        """)
                .param("idTransacao", idTransacao)
                .param("idEmpresa", idEmpresa)
                .param("idCompra", idCompra)
                .param("idPagamento", idPagamento)
                .param("valor", valor)
                .param("moeda", moeda)
                .param("agora", DatasSql.gravar(agora))
                .update();
    }

    public void rejeitar(
            UUID idTransacao,
            UUID idEmpresa,
            UUID idCompra,
            UUID idPagamento,
            BigDecimal valor,
            String moeda,
            String motivo,
            Instant agora) {
        banco.sql("""
                        INSERT INTO transacao_contabil (
                            id_transacao, id_empresa, id_compra, id_pagamento,
                            valor, moeda, status, motivo, criada_em
                        ) VALUES (
                            :idTransacao, :idEmpresa, :idCompra, :idPagamento,
                            :valor, :moeda, 'REJEITADA', :motivo, :agora
                        )
                        """)
                .param("idTransacao", idTransacao)
                .param("idEmpresa", idEmpresa)
                .param("idCompra", idCompra)
                .param("idPagamento", idPagamento)
                .param("valor", valor)
                .param("moeda", moeda)
                .param("motivo", motivo)
                .param("agora", DatasSql.gravar(agora))
                .update();
    }

    public void lancar(
            UUID idTransacao,
            String conta,
            NaturezaLancamento natureza,
            BigDecimal valor,
            String moeda,
            Instant agora) {
        banco.sql("""
                        INSERT INTO lancamento_contabil (
                            id_lancamento, id_transacao, conta, natureza,
                            valor, moeda, criado_em
                        ) VALUES (:id, :idTransacao, :conta, :natureza, :valor, :moeda, :agora)
                        """)
                .param("id", UUID.randomUUID())
                .param("idTransacao", idTransacao)
                .param("conta", conta)
                .param("natureza", natureza.name())
                .param("valor", valor)
                .param("moeda", moeda)
                .param("agora", DatasSql.gravar(agora))
                .update();
    }

    public void fechar(UUID idTransacao) {
        banco.sql("""
                        UPDATE transacao_contabil SET status = 'REGISTRADA'
                         WHERE id_transacao = :idTransacao AND status = 'ABERTA'
                        """)
                .param("idTransacao", idTransacao)
                .update();
    }

    public Optional<RespostaTransacaoContabil> buscar(UUID idEmpresa, UUID idCompra) {
        return banco.sql("""
                        SELECT id_transacao, id_compra, id_pagamento, valor,
                               moeda, status, motivo, criada_em
                          FROM transacao_contabil
                         WHERE id_empresa = :idEmpresa AND id_compra = :idCompra
                        """)
                .param("idEmpresa", idEmpresa)
                .param("idCompra", idCompra)
                .query((resultado, linha) -> {
                    UUID idTransacao = resultado.getObject("id_transacao", UUID.class);
                    return new RespostaTransacaoContabil(
                            idTransacao,
                            resultado.getObject("id_compra", UUID.class),
                            resultado.getObject("id_pagamento", UUID.class),
                            resultado.getBigDecimal("valor"),
                            resultado.getString("moeda"),
                            resultado.getString("status"),
                            resultado.getString("motivo"),
                            DatasSql.ler(resultado, "criada_em"),
                            buscarLancamentos(idTransacao));
                })
                .optional();
    }

    private List<Lancamento> buscarLancamentos(UUID idTransacao) {
        return banco.sql("""
                        SELECT id_lancamento, conta, natureza, valor, moeda, criado_em
                          FROM lancamento_contabil
                         WHERE id_transacao = :idTransacao
                         ORDER BY natureza, conta
                        """)
                .param("idTransacao", idTransacao)
                .query((resultado, linha) -> new Lancamento(
                        resultado.getObject("id_lancamento", UUID.class),
                        resultado.getString("conta"),
                        resultado.getString("natureza"),
                        resultado.getBigDecimal("valor"),
                        resultado.getString("moeda"),
                        DatasSql.ler(resultado, "criado_em")))
                .list();
    }
}
