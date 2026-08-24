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
                            canal, destinatario, assunto, mensagem, status, criada_em
                        ) VALUES (
                            :id, :idEvento, :idEmpresa, :idCompra,
                            'EMAIL', :destinatario, :assunto, :mensagem, 'PENDENTE', :agora
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

    public List<NotificacaoPendente> bloquearPendentes(int limite) {
        return banco.sql("""
                        SELECT id_notificacao, destinatario, assunto, mensagem
                          FROM notificacao
                         WHERE status = 'PENDENTE' AND tentativas < 5
                         ORDER BY criada_em
                         FOR UPDATE SKIP LOCKED
                         LIMIT :limite
                        """)
                .param("limite", limite)
                .query((resultado, linha) -> new NotificacaoPendente(
                        resultado.getObject("id_notificacao", UUID.class),
                        resultado.getString("destinatario"),
                        resultado.getString("assunto"),
                        resultado.getString("mensagem")))
                .list();
    }

    public void marcarEnviada(UUID idNotificacao, Instant agora) {
        banco.sql("""
                        UPDATE notificacao
                           SET status = 'ENVIADA', tentativas = tentativas + 1,
                               enviada_em = :agora, ultimo_erro = NULL
                         WHERE id_notificacao = :id
                        """)
                .param("id", idNotificacao)
                .param("agora", DatasSql.gravar(agora))
                .update();
    }

    public void registrarFalha(UUID idNotificacao, String erro) {
        banco.sql("""
                        UPDATE notificacao
                           SET tentativas = tentativas + 1, ultimo_erro = :erro
                         WHERE id_notificacao = :id
                        """)
                .param("id", idNotificacao)
                .param("erro", erro)
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
            String mensagem) {
    }
}
