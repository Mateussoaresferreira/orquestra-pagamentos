package br.com.orquestrapay.ledger.data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import br.com.orquestrapay.ledger.api.Lancamento;
import br.com.orquestrapay.ledger.api.ParcelaRecebivel;
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

    public void bloquearCompra(UUID idEmpresa, UUID idCompra) {
        banco.sql("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(:valor, 0))")
                .param("valor", idEmpresa + ":" + idCompra)
                .query(Integer.class)
                .single();
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

    public void agendarParcela(
            UUID idTransacao,
            int numero,
            int totalParcelas,
            BigDecimal valor,
            LocalDate vencimento,
            Instant agora) {
        banco.sql("""
                        INSERT INTO parcela_recebivel (
                            id_parcela, id_transacao, numero, total_parcelas,
                            valor, vencimento, status, criada_em
                        ) VALUES (
                            :idParcela, :idTransacao, :numero, :totalParcelas,
                            :valor, :vencimento, 'AGENDADA', :agora
                        )
                        ON CONFLICT (id_transacao, numero) DO NOTHING
                        """)
                .param("idParcela", UUID.randomUUID())
                .param("idTransacao", idTransacao)
                .param("numero", numero)
                .param("totalParcelas", totalParcelas)
                .param("valor", valor)
                .param("vencimento", vencimento)
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
                    List<Lancamento> lancamentos = buscarLancamentos(idTransacao);
                    return new RespostaTransacaoContabil(
                            idTransacao,
                            resultado.getObject("id_compra", UUID.class),
                            resultado.getObject("id_pagamento", UUID.class),
                            resultado.getBigDecimal("valor"),
                            resultado.getString("moeda"),
                            resultado.getString("status"),
                            resultado.getString("motivo"),
                            DatasSql.ler(resultado, "criada_em"),
                            somar(lancamentos, "DEBITO"),
                            somar(lancamentos, "CREDITO"),
                            lancamentos,
                            buscarParcelas(idTransacao));
                })
                .optional();
    }

    public List<ParcelaRecebivel> buscarParcelas(UUID idTransacao) {
        return banco.sql("""
                        SELECT id_parcela, numero, total_parcelas, valor, vencimento,
                               status, referencia_liquidacao, criada_em, liquidada_em
                          FROM parcela_recebivel
                         WHERE id_transacao = :idTransacao
                         ORDER BY numero
                        """)
                .param("idTransacao", idTransacao)
                .query(this::mapearParcela)
                .list();
    }

    public Optional<ParcelaRecebivel> bloquearParcela(
            UUID idEmpresa,
            UUID idCompra,
            int numero) {
        return banco.sql("""
                        SELECT p.id_parcela, p.numero, p.total_parcelas, p.valor, p.vencimento,
                               p.status, p.referencia_liquidacao, p.criada_em, p.liquidada_em
                          FROM parcela_recebivel p
                          JOIN transacao_contabil t ON t.id_transacao = p.id_transacao
                         WHERE t.id_empresa = :idEmpresa
                           AND t.id_compra = :idCompra
                           AND p.numero = :numero
                         FOR UPDATE OF p
                        """)
                .param("idEmpresa", idEmpresa)
                .param("idCompra", idCompra)
                .param("numero", numero)
                .query(this::mapearParcela)
                .optional();
    }

    public void liquidarParcela(UUID idParcela, String referencia, Instant agora) {
        int alteradas = banco.sql("""
                        UPDATE parcela_recebivel
                           SET status = 'LIQUIDADA',
                               referencia_liquidacao = :referencia,
                               liquidada_em = :agora
                         WHERE id_parcela = :idParcela
                           AND status = 'AGENDADA'
                        """)
                .param("idParcela", idParcela)
                .param("referencia", referencia)
                .param("agora", DatasSql.gravar(agora))
                .update();
        if (alteradas != 1) {
            throw new IllegalStateException("A parcela nao estava disponivel para liquidacao");
        }
        banco.sql("""
                        INSERT INTO auditoria_parcela (
                            id_auditoria, id_parcela, status_anterior,
                            status_novo, referencia, registrada_em
                        ) VALUES (
                            :idAuditoria, :idParcela, 'AGENDADA',
                            'LIQUIDADA', :referencia, :agora
                        )
                        """)
                .param("idAuditoria", UUID.randomUUID())
                .param("idParcela", idParcela)
                .param("referencia", referencia)
                .param("agora", DatasSql.gravar(agora))
                .update();
    }

    private ParcelaRecebivel mapearParcela(java.sql.ResultSet resultado, int linha)
            throws java.sql.SQLException {
        return new ParcelaRecebivel(
                resultado.getObject("id_parcela", UUID.class),
                resultado.getInt("numero"),
                resultado.getInt("total_parcelas"),
                resultado.getBigDecimal("valor"),
                resultado.getObject("vencimento", LocalDate.class),
                resultado.getString("status"),
                resultado.getString("referencia_liquidacao"),
                DatasSql.ler(resultado, "criada_em"),
                DatasSql.ler(resultado, "liquidada_em"));
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

    private BigDecimal somar(List<Lancamento> lancamentos, String natureza) {
        return lancamentos.stream()
                .filter(lancamento -> natureza.equals(lancamento.natureza()))
                .map(Lancamento::valor)
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }
}
