package br.com.orquestrapay.platform.event;

import java.time.Instant;
import java.sql.Types;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

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

    public List<EventoPendente> reivindicarPendentes(
            int limite,
            int maximoTentativas,
            Instant agora,
            Instant bloqueadoAte) {
        UUID tokenBloqueio = UUID.randomUUID();
        List<EventoPendente> eventos = banco.sql("""
                        WITH selecionados AS (
                            SELECT atual.id_evento
                              FROM evento_saida atual
                             WHERE atual.publicado_em IS NULL
                               AND atual.descartado_em IS NULL
                               AND atual.proxima_tentativa_em <= :agora
                               AND (atual.bloqueado_ate IS NULL OR atual.bloqueado_ate <= :agora)
                               AND atual.tentativas < :maximoTentativas
                               AND NOT EXISTS (
                                   SELECT 1
                                     FROM evento_saida anterior
                                    WHERE anterior.id_compra = atual.id_compra
                                      AND anterior.ordem < atual.ordem
                                      AND anterior.publicado_em IS NULL
                               )
                             ORDER BY atual.ordem
                             FOR UPDATE OF atual SKIP LOCKED
                             LIMIT :limite
                        )
                        UPDATE evento_saida evento
                           SET bloqueado_ate = :bloqueadoAte,
                               token_bloqueio = :tokenBloqueio
                          FROM selecionados
                         WHERE evento.id_evento = selecionados.id_evento
                        RETURNING evento.id_evento, evento.ordem, evento.tipo, evento.versao,
                                  evento.id_correlacao, evento.id_empresa, evento.id_compra,
                                  evento.origem, evento.conteudo, evento.traceparent,
                                  evento.ocorrido_em, evento.tentativas, evento.token_bloqueio
                        """)
                .param("limite", limite)
                .param("maximoTentativas", maximoTentativas)
                .param("agora", DatasSql.gravar(agora))
                .param("bloqueadoAte", DatasSql.gravar(bloqueadoAte))
                .param("tokenBloqueio", tokenBloqueio)
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
                        resultado.getInt("tentativas"),
                        resultado.getObject("token_bloqueio", UUID.class)))
                .list();
        return eventos.stream()
                .sorted(Comparator.comparingLong(EventoPendente::ordem))
                .toList();
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

    public PaginaQuarentena listarQuarentena(UUID idEmpresa, int pagina, int tamanho) {
        long total = banco.sql("""
                        SELECT COUNT(*)
                          FROM evento_saida
                         WHERE id_empresa = :idEmpresa AND descartado_em IS NOT NULL
                        """)
                .param("idEmpresa", idEmpresa)
                .query(Long.class)
                .single();
        List<EventoQuarentena> itens = banco.sql("""
                        SELECT id_evento, tipo, versao, id_correlacao, id_compra,
                               origem, tentativas, ultimo_erro, ocorrido_em, descartado_em
                          FROM evento_saida
                         WHERE id_empresa = :idEmpresa AND descartado_em IS NOT NULL
                         ORDER BY descartado_em DESC, id_evento
                         LIMIT :tamanho OFFSET :deslocamento
                        """)
                .param("idEmpresa", idEmpresa)
                .param("tamanho", tamanho)
                .param("deslocamento", pagina * tamanho)
                .query((resultado, linha) -> new EventoQuarentena(
                        resultado.getObject("id_evento", UUID.class),
                        resultado.getString("tipo"),
                        resultado.getInt("versao"),
                        resultado.getObject("id_correlacao", UUID.class),
                        resultado.getObject("id_compra", UUID.class),
                        resultado.getString("origem"),
                        resultado.getInt("tentativas"),
                        abreviar(resultado.getString("ultimo_erro")),
                        DatasSql.ler(resultado, "ocorrido_em"),
                        DatasSql.ler(resultado, "descartado_em")))
                .list();
        return new PaginaQuarentena(itens, pagina, tamanho, total);
    }

    public boolean reprocessarQuarentena(
            UUID idEmpresa,
            UUID idEvento,
            String responsavel,
            Instant agora) {
        int atualizados = banco.sql("""
                        UPDATE evento_saida
                           SET tentativas = 0,
                               ultimo_erro = NULL,
                               proxima_tentativa_em = :agora,
                               descartado_em = NULL,
                               bloqueado_ate = NULL,
                               token_bloqueio = NULL
                         WHERE id_evento = :idEvento
                           AND id_empresa = :idEmpresa
                           AND descartado_em IS NOT NULL
                        """)
                .param("idEvento", idEvento)
                .param("idEmpresa", idEmpresa)
                .param("agora", DatasSql.gravar(agora))
                .update();
        if (atualizados == 0) {
            return false;
        }
        banco.sql("""
                        INSERT INTO auditoria_quarentena (
                            id_auditoria, id_evento, acao,
                            responsavel, detalhes, registrada_em
                        ) VALUES (
                            :idAuditoria, :idEvento, 'REPROCESSAR',
                            :responsavel, 'Tentativas reiniciadas pela API administrativa', :agora
                        )
                        """)
                .param("idAuditoria", UUID.randomUUID())
                .param("idEvento", idEvento)
                .param("responsavel", responsavel)
                .param("agora", DatasSql.gravar(agora))
                .update();
        return true;
    }

    public boolean marcarPublicado(UUID idEvento, UUID tokenBloqueio, Instant publicadoEm) {
        return banco.sql("""
                        UPDATE evento_saida
                           SET publicado_em = :publicadoEm,
                               tentativas = tentativas + 1,
                               ultimo_erro = NULL,
                               bloqueado_ate = NULL,
                               token_bloqueio = NULL
                         WHERE id_evento = :idEvento
                           AND token_bloqueio = :tokenBloqueio
                        """)
                .param("idEvento", idEvento)
                .param("tokenBloqueio", tokenBloqueio)
                .param("publicadoEm", DatasSql.gravar(publicadoEm))
                .update() == 1;
    }

    public int marcarPublicados(List<EventoPendente> eventos, Instant publicadoEm) {
        if (eventos.isEmpty()) {
            return 0;
        }

        Map<UUID, List<UUID>> idsPorToken = eventos.stream()
                .collect(Collectors.groupingBy(
                        EventoPendente::tokenBloqueio,
                        Collectors.mapping(EventoPendente::idEvento, Collectors.toList())));

        return idsPorToken.entrySet().stream()
                .mapToInt(grupo -> banco.sql("""
                                UPDATE evento_saida
                                   SET publicado_em = :publicadoEm,
                                       tentativas = tentativas + 1,
                                       ultimo_erro = NULL,
                                       bloqueado_ate = NULL,
                                       token_bloqueio = NULL
                                 WHERE token_bloqueio = :tokenBloqueio
                                   AND id_evento IN (:idsEventos)
                                """)
                        .param("publicadoEm", DatasSql.gravar(publicadoEm))
                        .param("tokenBloqueio", grupo.getKey())
                        .param("idsEventos", grupo.getValue())
                        .update())
                .sum();
    }

    public boolean registrarFalha(
            UUID idEvento,
            UUID tokenBloqueio,
            String erro,
            Instant proximaTentativaEm,
            Instant descartadoEm) {
        return banco.sql("""
                        UPDATE evento_saida
                           SET tentativas = tentativas + 1,
                               ultimo_erro = :erro,
                               proxima_tentativa_em = :proximaTentativaEm,
                               descartado_em = :descartadoEm,
                               bloqueado_ate = NULL,
                               token_bloqueio = NULL
                         WHERE id_evento = :idEvento
                           AND token_bloqueio = :tokenBloqueio
                        """)
                .param("idEvento", idEvento)
                .param("tokenBloqueio", tokenBloqueio)
                .param("erro", abreviar(erro))
                .param("proximaTentativaEm", DatasSql.gravar(proximaTentativaEm))
                .param("descartadoEm", DatasSql.gravar(descartadoEm), Types.TIMESTAMP_WITH_TIMEZONE)
                .update() == 1;
    }

    private String abreviar(String erro) {
        if (erro == null) {
            return "Falha sem mensagem";
        }
        return erro.length() <= 2_000 ? erro : erro.substring(0, 2_000);
    }
}
