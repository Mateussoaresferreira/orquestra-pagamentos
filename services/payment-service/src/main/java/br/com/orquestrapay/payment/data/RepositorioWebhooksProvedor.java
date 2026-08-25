package br.com.orquestrapay.payment.data;

import java.time.Instant;
import java.util.UUID;

import br.com.orquestrapay.platform.data.DatasSql;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RepositorioWebhooksProvedor {

    private final JdbcClient banco;

    public RepositorioWebhooksProvedor(JdbcClient banco) {
        this.banco = banco;
    }

    public ResultadoRegistro registrar(
            String provedor,
            UUID idEvento,
            String hashConteudo,
            Instant agora) {
        int inseridos = banco.sql("""
                        INSERT INTO webhook_provedor_recebido (
                            id_webhook, provedor, id_evento_provedor,
                            hash_conteudo, status_processamento, recebido_em
                        ) VALUES (
                            :idWebhook, :provedor, :idEvento,
                            :hashConteudo, 'RECEBIDO', :agora
                        )
                        ON CONFLICT (provedor, id_evento_provedor) DO NOTHING
                        """)
                .param("idWebhook", UUID.randomUUID())
                .param("provedor", provedor)
                .param("idEvento", idEvento)
                .param("hashConteudo", hashConteudo)
                .param("agora", DatasSql.gravar(agora))
                .update();
        if (inseridos == 1) {
            return ResultadoRegistro.NOVO;
        }

        String hashExistente = banco.sql("""
                        SELECT hash_conteudo
                          FROM webhook_provedor_recebido
                         WHERE provedor = :provedor
                           AND id_evento_provedor = :idEvento
                        """)
                .param("provedor", provedor)
                .param("idEvento", idEvento)
                .query(String.class)
                .single();
        return hashConteudo.equals(hashExistente)
                ? ResultadoRegistro.DUPLICADO
                : ResultadoRegistro.CONFLITANTE;
    }

    public void concluir(
            String provedor,
            UUID idEvento,
            UUID idPagamento,
            String status,
            String motivo,
            Instant agora) {
        var comando = banco.sql("""
                        UPDATE webhook_provedor_recebido
                           SET id_pagamento = :idPagamento,
                               status_processamento = :status,
                               motivo = :motivo,
                               processado_em = :agora
                         WHERE provedor = :provedor
                           AND id_evento_provedor = :idEvento
                        """)
                .param("status", status)
                .param("motivo", motivo)
                .param("agora", DatasSql.gravar(agora))
                .param("provedor", provedor)
                .param("idEvento", idEvento);
        if (idPagamento == null) {
            comando = comando.param("idPagamento", null, java.sql.Types.OTHER);
        } else {
            comando = comando.param("idPagamento", idPagamento);
        }
        comando.update();
    }

    public enum ResultadoRegistro {
        NOVO,
        DUPLICADO,
        CONFLITANTE
    }
}
