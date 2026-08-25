package br.com.orquestrapay.platform.event;

import java.time.Instant;

import br.com.orquestrapay.platform.data.DatasSql;
import org.springframework.jdbc.core.simple.JdbcClient;

public class RepositorioRetencaoEventos {

    private final JdbcClient banco;

    public RepositorioRetencaoEventos(JdbcClient banco) {
        this.banco = banco;
    }

    public int removerProcessadosAnterioresA(Instant limite, int tamanhoLote) {
        return banco.sql("""
                        WITH candidatos AS (
                            SELECT ctid
                              FROM evento_processado
                             WHERE processado_em < :limite
                             ORDER BY processado_em
                             LIMIT :tamanhoLote
                             FOR UPDATE SKIP LOCKED
                        )
                        DELETE FROM evento_processado processado
                         USING candidatos
                         WHERE processado.ctid = candidatos.ctid
                        """)
                .param("limite", DatasSql.gravar(limite))
                .param("tamanhoLote", tamanhoLote)
                .update();
    }

    public int removerPublicadosAnterioresA(Instant limite, int tamanhoLote) {
        return banco.sql("""
                        WITH candidatos AS (
                            SELECT evento.ctid
                              FROM evento_saida evento
                             WHERE evento.publicado_em < :limite
                               AND evento.descartado_em IS NULL
                               AND NOT EXISTS (
                                   SELECT 1
                                     FROM auditoria_quarentena auditoria
                                    WHERE auditoria.id_evento = evento.id_evento
                               )
                             ORDER BY evento.publicado_em
                             LIMIT :tamanhoLote
                             FOR UPDATE OF evento SKIP LOCKED
                        )
                        DELETE FROM evento_saida evento
                         USING candidatos
                         WHERE evento.ctid = candidatos.ctid
                        """)
                .param("limite", DatasSql.gravar(limite))
                .param("tamanhoLote", tamanhoLote)
                .update();
    }

    public int removerAuditoriasQuarentenaAnterioresA(Instant limite, int tamanhoLote) {
        return banco.sql("""
                        WITH candidatos AS (
                            SELECT ctid
                              FROM auditoria_quarentena
                             WHERE registrada_em < :limite
                             ORDER BY registrada_em
                             LIMIT :tamanhoLote
                             FOR UPDATE SKIP LOCKED
                        )
                        DELETE FROM auditoria_quarentena auditoria
                         USING candidatos
                         WHERE auditoria.ctid = candidatos.ctid
                        """)
                .param("limite", DatasSql.gravar(limite))
                .param("tamanhoLote", tamanhoLote)
                .update();
    }

    public int removerDescartadosAnterioresA(Instant limite, int tamanhoLote) {
        return banco.sql("""
                        WITH candidatos AS (
                            SELECT evento.ctid
                              FROM evento_saida evento
                             WHERE evento.descartado_em < :limite
                               AND NOT EXISTS (
                                   SELECT 1
                                     FROM auditoria_quarentena auditoria
                                    WHERE auditoria.id_evento = evento.id_evento
                               )
                             ORDER BY evento.descartado_em
                             LIMIT :tamanhoLote
                             FOR UPDATE OF evento SKIP LOCKED
                        )
                        DELETE FROM evento_saida evento
                         USING candidatos
                         WHERE evento.ctid = candidatos.ctid
                        """)
                .param("limite", DatasSql.gravar(limite))
                .param("tamanhoLote", tamanhoLote)
                .update();
    }
}
