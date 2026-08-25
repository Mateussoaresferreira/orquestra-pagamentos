package br.com.orquestrapay.notification.data;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import br.com.orquestrapay.notification.api.RespostaNotificacao;
import br.com.orquestrapay.platform.data.DatasSql;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RepositorioNotificacoes {

    private final JdbcClient banco;

    public RepositorioNotificacoes(JdbcClient banco) {
        this.banco = banco;
    }

    public void adicionar(
            UUID idEvento,
            UUID idEmpresa,
            UUID idCompra,
            String destinatario,
            String assunto,
            String mensagem,
            Instant agora) {
        banco.sql("""
                        INSERT INTO notificacao (
                            id_notificacao, id_evento, id_empresa, id_compra,
                            canal, destinatario, assunto, mensagem, status,
                            criada_em, proxima_tentativa_em
                        ) VALUES (
                            :id, :idEvento, :idEmpresa, :idCompra,
                            'EMAIL', :destinatario, :assunto, :mensagem, 'PENDENTE',
                            :agora, :agora
                        )
                        """)
                .param("id", UUID.randomUUID())
                .param("idEvento", idEvento)
                .param("idEmpresa", idEmpresa)
                .param("idCompra", idCompra)
                .param("destinatario", destinatario)
                .param("assunto", assunto)
                .param("mensagem", mensagem)
                .param("agora", DatasSql.gravar(agora))
                .update();
    }

    public List<NotificacaoPendente> reivindicarPendentes(
            int limite,
            int maximoTentativas,
            Instant agora,
            Instant bloqueadoAte) {
        List<NotificacaoPendente> notificacoes = banco.sql("""
                        SELECT id_notificacao, destinatario, assunto, mensagem, tentativas
                          FROM notificacao
                         WHERE tentativas < :maximoTentativas
                           AND (
                               (status = 'PENDENTE' AND proxima_tentativa_em <= :agora)
                               OR (status = 'PROCESSANDO' AND bloqueado_ate <= :agora)
                           )
                         ORDER BY proxima_tentativa_em, criada_em
                         FOR UPDATE SKIP LOCKED
                         LIMIT :limite
                        """)
                .param("limite", limite)
                .param("maximoTentativas", maximoTentativas)
                .param("agora", DatasSql.gravar(agora))
                .query((resultado, linha) -> new NotificacaoPendente(
                        resultado.getObject("id_notificacao", UUID.class),
                        resultado.getString("destinatario"),
                        resultado.getString("assunto"),
                        resultado.getString("mensagem"),
                        resultado.getInt("tentativas")))
                .list();
        for (NotificacaoPendente notificacao : notificacoes) {
            banco.sql("""
                            UPDATE notificacao
                               SET status = 'PROCESSANDO',
                                   tentativas = tentativas + 1,
                                   bloqueado_ate = :bloqueadoAte
                             WHERE id_notificacao = :id
                            """)
                    .param("id", notificacao.idNotificacao())
                    .param("bloqueadoAte", DatasSql.gravar(bloqueadoAte))
                    .update();
        }
        return notificacoes;
    }

    public void marcarEnviada(UUID idNotificacao, Instant agora) {
        banco.sql("""
                        UPDATE notificacao
                           SET status = 'ENVIADA', enviada_em = :agora,
                               ultimo_erro = NULL, bloqueado_ate = NULL
                         WHERE id_notificacao = :id AND status = 'PROCESSANDO'
                        """)
                .param("id", idNotificacao)
                .param("agora", DatasSql.gravar(agora))
                .update();
    }

    public void registrarFalha(
            UUID idNotificacao,
            String erro,
            Instant proximaTentativaEm,
            Instant falhaDefinitivaEm) {
        banco.sql("""
                        UPDATE notificacao
                           SET status = :status,
                               ultimo_erro = :erro,
                               proxima_tentativa_em = :proximaTentativaEm,
                               falha_definitiva_em = :falhaDefinitivaEm,
                               bloqueado_ate = NULL
                         WHERE id_notificacao = :id AND status = 'PROCESSANDO'
                        """)
                .param("id", idNotificacao)
                .param("status", falhaDefinitivaEm == null ? "PENDENTE" : "FALHA_DEFINITIVA")
                .param("erro", abreviar(erro))
                .param("proximaTentativaEm", DatasSql.gravar(proximaTentativaEm))
                .param("falhaDefinitivaEm", DatasSql.gravar(falhaDefinitivaEm), java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    public List<RespostaNotificacao> buscar(UUID idEmpresa, UUID idCompra) {
        return banco.sql("""
                        SELECT id_notificacao, id_compra, canal, destinatario,
                               assunto, status, tentativas, criada_em, enviada_em
                          FROM notificacao
                         WHERE id_empresa = :idEmpresa AND id_compra = :idCompra
                         ORDER BY criada_em
                        """)
                .param("idEmpresa", idEmpresa)
                .param("idCompra", idCompra)
                .query((resultado, linha) -> new RespostaNotificacao(
                        resultado.getObject("id_notificacao", UUID.class),
                        resultado.getObject("id_compra", UUID.class),
                        resultado.getString("canal"),
                        resultado.getString("destinatario"),
                        resultado.getString("assunto"),
                        resultado.getString("status"),
                        resultado.getInt("tentativas"),
                        DatasSql.ler(resultado, "criada_em"),
                        DatasSql.ler(resultado, "enviada_em")))
                .list();
    }

    public record NotificacaoPendente(
            UUID idNotificacao,
            String destinatario,
            String assunto,
            String mensagem,
            int tentativas) {
    }

    private String abreviar(String erro) {
        if (erro == null || erro.isBlank()) {
            return "Falha sem mensagem";
        }
        return erro.length() <= 2_000 ? erro : erro.substring(0, 2_000);
    }
}
