package br.com.orquestrapay.platform.event;

import java.time.Instant;
import java.sql.Types;
import java.util.List;
import java.util.UUID;

import br.com.orquestrapay.platform.data.DatasSql;
import org.springframework.jdbc.core.simple.JdbcClient;

public class RepositorioEventos {

    private final JdbcClient banco;

    public RepositorioEventos(JdbcClient banco) {
        this.banco = banco;
    }

    public long adicionar(
            UUID idEvento,
            String tipo,
            int versao,
            UUID idCorrelacao,
            UUID idEmpresa,
            UUID idCompra,
            String origem,
            String conteudo,
            String traceparent,
            Instant ocorridoEm) {
        return banco.sql("""
                        INSERT INTO evento_saida (
                            id_evento, tipo, versao, id_correlacao, id_empresa,
                            id_compra, origem, conteudo, traceparent, ocorrido_em
                        ) VALUES (
                            :idEvento, :tipo, :versao, :idCorrelacao, :idEmpresa,
                            :idCompra, :origem, :conteudo, :traceparent, :ocorridoEm
                        )
                        RETURNING ordem
                        """)
                .param("idEvento", idEvento)
                .param("tipo", tipo)
                .param("versao", versao)
                .param("idCorrelacao", idCorrelacao)
                .param("idEmpresa", idEmpresa)
                .param("idCompra", idCompra)
                .param("origem", origem)
                .param("conteudo", conteudo)
                .param("traceparent", traceparent)
                .param("ocorridoEm", DatasSql.gravar(ocorridoEm))
                .query(Long.class)
                .single();
    }

    public List<EventoPendente> buscarPendentes(int limite, int maximoTentativas) {
        return banco.sql("""
                        SELECT id_evento, ordem, tipo, versao, id_correlacao,
                               id_empresa, id_compra, origem, conteudo,
                               traceparent, ocorrido_em, tentativas
                          FROM evento_saida
                         WHERE publicado_em IS NULL
                           AND descartado_em IS NULL
                           AND proxima_tentativa_em <= CURRENT_TIMESTAMP
                           AND tentativas < :maximoTentativas
                         ORDER BY ordem
                         FOR UPDATE SKIP LOCKED
                         LIMIT :limite
                        """)
                .param("limite", limite)
                .param("maximoTentativas", maximoTentativas)
                .query((resultado, numeroLinha) -> new EventoPendente(
                        resultado.getObject("id_evento", UUID.class),
                        resultado.getLong("ordem"),
                        resultado.getString("tipo"),
                        resultado.getInt("versao"),
                        resultado.getObject("id_correlacao", UUID.class),
                        resultado.getObject("id_empresa", UUID.class),
                        resultado.getObject("id_compra", UUID.class),
                        resultado.getString("origem"),
                        resultado.getString("conteudo"),
                        resultado.getString("traceparent"),
                        DatasSql.ler(resultado, "ocorrido_em"),
                        resultado.getInt("tentativas")))
                .list();
    }

    public ResumoOutbox resumir() {
        return banco.sql("""
                        SELECT COUNT(*) FILTER (
                                   WHERE publicado_em IS NULL
                                     AND descartado_em IS NULL
                               ) AS pendentes,
                               COUNT(*) FILTER (
                                   WHERE descartado_em IS NOT NULL
                               ) AS quarentena,
                               COALESCE(
                                   EXTRACT(EPOCH FROM (
                                       CURRENT_TIMESTAMP - MIN(ocorrido_em) FILTER (
                                           WHERE publicado_em IS NULL
                                             AND descartado_em IS NULL
                                       )
                                   )),
                                   0
                               ) AS idade_mais_antiga_segundos
                          FROM evento_saida
                        """)
                .query((resultado, numeroLinha) -> new ResumoOutbox(
                        resultado.getLong("pendentes"),
                        resultado.getLong("quarentena"),
                        resultado.getDouble("idade_mais_antiga_segundos")))
                .single();
    }

    public void marcarPublicado(UUID idEvento, Instant publicadoEm) {
        banco.sql("""
                        UPDATE evento_saida
                           SET publicado_em = :publicadoEm,
                               tentativas = tentativas + 1,
                               ultimo_erro = NULL
                         WHERE id_evento = :idEvento
                        """)
                .param("idEvento", idEvento)
                .param("publicadoEm", DatasSql.gravar(publicadoEm))
                .update();
    }

    public void registrarFalha(
            UUID idEvento,
            String erro,
            Instant proximaTentativaEm,
            Instant descartadoEm) {
        banco.sql("""
                        UPDATE evento_saida
                           SET tentativas = tentativas + 1,
                               ultimo_erro = :erro,
                               proxima_tentativa_em = :proximaTentativaEm,
                               descartado_em = :descartadoEm
                         WHERE id_evento = :idEvento
                        """)
                .param("idEvento", idEvento)
                .param("erro", abreviar(erro))
                .param("proximaTentativaEm", DatasSql.gravar(proximaTentativaEm))
                .param("descartadoEm", DatasSql.gravar(descartadoEm), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    private String abreviar(String erro) {
        if (erro == null) {
            return "Falha sem mensagem";
        }
        return erro.length() <= 2_000 ? erro : erro.substring(0, 2_000);
    }
}
