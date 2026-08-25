package br.com.orquestrapay.notification.data;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import br.com.orquestrapay.notification.api.PaginaEntregasWebhook;
import br.com.orquestrapay.notification.api.RespostaConfiguracaoWebhook;
import br.com.orquestrapay.notification.api.RespostaEntregaWebhook;
import br.com.orquestrapay.platform.data.DatasSql;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RepositorioWebhooks {

    private final JdbcClient banco;

    public RepositorioWebhooks(JdbcClient banco) {
        this.banco = banco;
    }

    public void salvarConfiguracao(
            UUID idEmpresa,
            String url,
            String segredoProtegido,
            Set<String> eventos,
            boolean ativo,
            Instant agora) {
        banco.sql("""
                        INSERT INTO configuracao_webhook (
                            id_empresa, url, segredo_protegido, eventos,
                            ativo, criado_em, atualizado_em
                        ) VALUES (
                            :idEmpresa, :url, :segredo, :eventos,
                            :ativo, :agora, :agora
                        )
                        ON CONFLICT (id_empresa) DO UPDATE
                           SET url = EXCLUDED.url,
                               segredo_protegido = EXCLUDED.segredo_protegido,
                               eventos = EXCLUDED.eventos,
                               ativo = EXCLUDED.ativo,
                               atualizado_em = EXCLUDED.atualizado_em
                        """)
                .param("idEmpresa", idEmpresa)
                .param("url", url)
                .param("segredo", segredoProtegido)
                .param("eventos", serializar(eventos))
                .param("ativo", ativo)
                .param("agora", DatasSql.gravar(agora))
                .update();
    }

    public Optional<Configuracao> buscarConfiguracao(UUID idEmpresa) {
        return banco.sql("""
                        SELECT id_empresa, url, segredo_protegido, eventos, ativo, atualizado_em
                          FROM configuracao_webhook
                         WHERE id_empresa = :idEmpresa
                        """)
                .param("idEmpresa", idEmpresa)
                .query((resultado, linha) -> new Configuracao(
                        resultado.getObject("id_empresa", UUID.class),
                        resultado.getString("url"),
                        resultado.getString("segredo_protegido"),
                        desserializar(resultado.getString("eventos")),
                        resultado.getBoolean("ativo"),
                        DatasSql.ler(resultado, "atualizado_em")))
                .optional();
    }

    public void desabilitar(UUID idEmpresa, Instant agora) {
        banco.sql("""
                        UPDATE configuracao_webhook
                           SET ativo = FALSE, atualizado_em = :agora
                         WHERE id_empresa = :idEmpresa
                        """)
                .param("idEmpresa", idEmpresa)
                .param("agora", DatasSql.gravar(agora))
                .update();
    }

    public void agendar(
            UUID idEmpresa,
            UUID idEvento,
            UUID idCompra,
            String tipoEvento,
            String conteudo,
            Instant agora) {
        banco.sql("""
                        INSERT INTO entrega_webhook (
                            id_entrega, id_empresa, id_evento, id_compra,
                            tipo_evento, conteudo, status, proxima_tentativa_em,
                            criada_em, atualizada_em
                        ) VALUES (
                            :idEntrega, :idEmpresa, :idEvento, :idCompra,
                            :tipoEvento, :conteudo, 'PENDENTE', :agora,
                            :agora, :agora
                        )
                        ON CONFLICT (id_empresa, id_evento) DO NOTHING
                        """)
                .param("idEntrega", UUID.randomUUID())
                .param("idEmpresa", idEmpresa)
                .param("idEvento", idEvento)
                .param("idCompra", idCompra)
                .param("tipoEvento", tipoEvento)
                .param("conteudo", conteudo)
                .param("agora", DatasSql.gravar(agora))
                .update();
    }

    public List<EntregaPendente> reivindicar(
            int limite,
            Instant agora,
            Instant bloqueadoAte,
            UUID tokenBloqueio) {
        List<UUID> ids = banco.sql("""
                        SELECT id_entrega
                          FROM entrega_webhook
                         WHERE (status = 'PENDENTE'
                                OR (status = 'PROCESSANDO' AND bloqueado_ate <= :agora))
                           AND proxima_tentativa_em <= :agora
                         ORDER BY proxima_tentativa_em, criada_em
                         FOR UPDATE SKIP LOCKED
                         LIMIT :limite
                        """)
                .param("agora", DatasSql.gravar(agora))
                .param("limite", limite)
                .query(UUID.class)
                .list();
        if (ids.isEmpty()) {
            return List.of();
        }

        banco.sql("""
                        UPDATE entrega_webhook
                           SET status = 'PROCESSANDO',
                               tentativas = tentativas + 1,
                               bloqueado_ate = :bloqueadoAte,
                               token_bloqueio = :tokenBloqueio,
                               atualizada_em = :agora
                         WHERE id_entrega IN (:ids)
                        """)
                .param("bloqueadoAte", DatasSql.gravar(bloqueadoAte))
                .param("tokenBloqueio", tokenBloqueio)
                .param("agora", DatasSql.gravar(agora))
                .param("ids", ids)
                .update();

        return banco.sql("""
                        SELECT e.id_entrega, e.id_empresa, e.id_evento,
                               e.id_compra, e.tipo_evento, e.conteudo,
                               e.tentativas, e.token_bloqueio,
                               c.url, c.segredo_protegido
                          FROM entrega_webhook e
                          JOIN configuracao_webhook c ON c.id_empresa = e.id_empresa
                         WHERE e.id_entrega IN (:ids)
                           AND e.token_bloqueio = :tokenBloqueio
                         ORDER BY e.criada_em
                        """)
                .param("ids", ids)
                .param("tokenBloqueio", tokenBloqueio)
                .query((resultado, linha) -> new EntregaPendente(
                        resultado.getObject("id_entrega", UUID.class),
                        resultado.getObject("id_empresa", UUID.class),
                        resultado.getObject("id_evento", UUID.class),
                        resultado.getObject("id_compra", UUID.class),
                        resultado.getString("tipo_evento"),
                        resultado.getString("conteudo"),
                        resultado.getString("url"),
                        resultado.getString("segredo_protegido"),
                        resultado.getInt("tentativas"),
                        resultado.getObject("token_bloqueio", UUID.class)))
                .list();
    }

    public boolean marcarEntregue(
            EntregaPendente entrega,
            int statusHttp,
            Instant agora) {
        boolean atualizado = banco.sql("""
                        UPDATE entrega_webhook
                           SET status = 'ENTREGUE',
                               ultimo_status_http = :statusHttp,
                               ultimo_erro = NULL,
                               bloqueado_ate = NULL,
                               token_bloqueio = NULL,
                               atualizada_em = :agora,
                               entregue_em = :agora
                         WHERE id_entrega = :idEntrega
                           AND status = 'PROCESSANDO'
                           AND token_bloqueio = :tokenBloqueio
                        """)
                .param("statusHttp", statusHttp)
                .param("agora", DatasSql.gravar(agora))
                .param("idEntrega", entrega.idEntrega())
                .param("tokenBloqueio", entrega.tokenBloqueio())
                .update() == 1;
        if (atualizado) {
            registrarTentativa(entrega, statusHttp, "SUCESSO", null, agora);
        }
        return atualizado;
    }

    public boolean registrarFalha(
            EntregaPendente entrega,
            Integer statusHttp,
            String erro,
            Instant proximaTentativa,
            boolean definitiva,
            Instant agora) {
        boolean atualizado = banco.sql("""
                        UPDATE entrega_webhook
                           SET status = :status,
                               proxima_tentativa_em = :proximaTentativa,
                               ultimo_status_http = :statusHttp,
                               ultimo_erro = :erro,
                               bloqueado_ate = NULL,
                               token_bloqueio = NULL,
                               atualizada_em = :agora,
                               falha_definitiva_em = :falhaDefinitiva
                         WHERE id_entrega = :idEntrega
                           AND status = 'PROCESSANDO'
                           AND token_bloqueio = :tokenBloqueio
                        """)
                .param("status", definitiva ? "FALHA_DEFINITIVA" : "PENDENTE")
                .param("proximaTentativa", DatasSql.gravar(proximaTentativa))
                .param("statusHttp", statusHttp, java.sql.Types.INTEGER)
                .param("erro", abreviar(erro))
                .param("agora", DatasSql.gravar(agora))
                .param("falhaDefinitiva", DatasSql.gravar(definitiva ? agora : null),
                        java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                .param("idEntrega", entrega.idEntrega())
                .param("tokenBloqueio", entrega.tokenBloqueio())
                .update() == 1;
        if (atualizado) {
            registrarTentativa(
                    entrega,
                    statusHttp,
                    definitiva ? "FALHA_DEFINITIVA" : "FALHA_TRANSITORIA",
                    erro,
                    agora);
        }
        return atualizado;
    }

    public PaginaEntregasWebhook listar(UUID idEmpresa, int pagina, int tamanho) {
        long total = banco.sql("""
                        SELECT COUNT(*) FROM entrega_webhook WHERE id_empresa = :idEmpresa
                        """)
                .param("idEmpresa", idEmpresa)
                .query(Long.class)
                .single();
        List<RespostaEntregaWebhook> itens = banco.sql("""
                        SELECT id_entrega, id_evento, id_compra, tipo_evento,
                               status, tentativas, ultimo_status_http, ultimo_erro,
                               criada_em, entregue_em, falha_definitiva_em
                          FROM entrega_webhook
                         WHERE id_empresa = :idEmpresa
                         ORDER BY criada_em DESC, id_entrega
                         LIMIT :limite OFFSET :deslocamento
                        """)
                .param("idEmpresa", idEmpresa)
                .param("limite", tamanho)
                .param("deslocamento", (long) pagina * tamanho)
                .query(this::mapearResposta)
                .list();
        return new PaginaEntregasWebhook(itens, pagina, tamanho, total);
    }

    public boolean reprocessar(UUID idEmpresa, UUID idEntrega, Instant agora) {
        return banco.sql("""
                        UPDATE entrega_webhook
                           SET status = 'PENDENTE',
                               tentativas = 0,
                               proxima_tentativa_em = :agora,
                               bloqueado_ate = NULL,
                               token_bloqueio = NULL,
                               ultimo_erro = NULL,
                               ultimo_status_http = NULL,
                               falha_definitiva_em = NULL,
                               atualizada_em = :agora
                         WHERE id_empresa = :idEmpresa
                           AND id_entrega = :idEntrega
                           AND status = 'FALHA_DEFINITIVA'
                        """)
                .param("idEmpresa", idEmpresa)
                .param("idEntrega", idEntrega)
                .param("agora", DatasSql.gravar(agora))
                .update() == 1;
    }

    private void registrarTentativa(
            EntregaPendente entrega,
            Integer statusHttp,
            String resultado,
            String detalhes,
            Instant agora) {
        banco.sql("""
                        INSERT INTO tentativa_webhook (
                            id_tentativa, id_entrega, numero_tentativa,
                            status_http, resultado, detalhes, realizada_em
                        ) VALUES (
                            :idTentativa, :idEntrega, :numeroTentativa,
                            :statusHttp, :resultado, :detalhes, :agora
                        )
                        """)
                .param("idTentativa", UUID.randomUUID())
                .param("idEntrega", entrega.idEntrega())
                .param("numeroTentativa", entrega.tentativas())
                .param("statusHttp", statusHttp, java.sql.Types.INTEGER)
                .param("resultado", resultado)
                .param("detalhes", abreviar(detalhes))
                .param("agora", DatasSql.gravar(agora))
                .update();
    }

    private RespostaEntregaWebhook mapearResposta(ResultSet resultado, int linha) throws SQLException {
        return new RespostaEntregaWebhook(
                resultado.getObject("id_entrega", UUID.class),
                resultado.getObject("id_evento", UUID.class),
                resultado.getObject("id_compra", UUID.class),
                resultado.getString("tipo_evento"),
                resultado.getString("status"),
                resultado.getInt("tentativas"),
                resultado.getObject("ultimo_status_http", Integer.class),
                resultado.getString("ultimo_erro"),
                DatasSql.ler(resultado, "criada_em"),
                DatasSql.ler(resultado, "entregue_em"),
                DatasSql.ler(resultado, "falha_definitiva_em"));
    }

    private String serializar(Set<String> eventos) {
        return new TreeSet<>(eventos).stream().collect(Collectors.joining(","));
    }

    private Set<String> desserializar(String eventos) {
        if (eventos == null || eventos.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(eventos.split(",")).collect(Collectors.toUnmodifiableSet());
    }

    private String abreviar(String texto) {
        if (texto == null || texto.length() <= 2_000) {
            return texto;
        }
        return texto.substring(0, 2_000);
    }

    public record Configuracao(
            UUID idEmpresa,
            String url,
            String segredoProtegido,
            Set<String> eventos,
            boolean ativo,
            Instant atualizadoEm) {

        public RespostaConfiguracaoWebhook resposta() {
            return new RespostaConfiguracaoWebhook(url, eventos, ativo, atualizadoEm);
        }
    }

    public record EntregaPendente(
            UUID idEntrega,
            UUID idEmpresa,
            UUID idEvento,
            UUID idCompra,
            String tipoEvento,
            String conteudo,
            String url,
            String segredoProtegido,
            int tentativas,
            UUID tokenBloqueio) {
    }
}
