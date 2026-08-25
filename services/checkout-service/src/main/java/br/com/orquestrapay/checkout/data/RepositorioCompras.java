package br.com.orquestrapay.checkout.data;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import br.com.orquestrapay.checkout.api.RegistroHistorico;
import br.com.orquestrapay.checkout.domain.Compra;
import br.com.orquestrapay.checkout.domain.StatusCompra;
import br.com.orquestrapay.contracts.ItemCompra;
import br.com.orquestrapay.platform.data.DatasSql;
import br.com.orquestrapay.platform.security.ProtecaoTokenPagamento;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RepositorioCompras {

    private final JdbcClient banco;
    private final ProtecaoTokenPagamento protecaoToken;

    public RepositorioCompras(JdbcClient banco, ProtecaoTokenPagamento protecaoToken) {
        this.banco = banco;
        this.protecaoToken = protecaoToken;
    }

    public void bloquearIdempotencia(UUID idEmpresa, String chave) {
        banco.sql("SELECT pg_advisory_xact_lock(hashtext(:valor))")
                .param("valor", idEmpresa + ":" + chave)
                .query((resultado, linha) -> Boolean.TRUE)
                .single();
    }

    public Optional<IdempotenciaExistente> buscarIdempotencia(UUID idEmpresa, String chave) {
        return banco.sql("""
                        SELECT hash_requisicao, id_compra
                          FROM requisicao_idempotente
                         WHERE id_empresa = :idEmpresa AND chave = :chave
                        """)
                .param("idEmpresa", idEmpresa)
                .param("chave", chave)
                .query((resultado, linha) -> new IdempotenciaExistente(
                        resultado.getString("hash_requisicao"),
                        resultado.getObject("id_compra", UUID.class)))
                .optional();
    }

    public int removerIdempotenciasAnterioresA(Instant limite, int tamanhoLote) {
        return banco.sql("""
                        WITH candidatos AS (
                            SELECT ctid
                              FROM requisicao_idempotente
                             WHERE criada_em < :limite
                             ORDER BY criada_em
                             LIMIT :tamanhoLote
                             FOR UPDATE SKIP LOCKED
                        )
                        DELETE FROM requisicao_idempotente idempotencia
                         USING candidatos
                         WHERE idempotencia.ctid = candidatos.ctid
                        """)
                .param("limite", DatasSql.gravar(limite))
                .param("tamanhoLote", tamanhoLote)
                .update();
    }

    public void adicionar(
            Compra compra,
            String tokenPagamento,
            String chaveIdempotencia,
            String hashRequisicao) {
        banco.sql("""
                        INSERT INTO compra (
                            id_compra, id_empresa, id_cliente, email_cliente, moeda,
                            pais, identificador_dispositivo, metodo_pagamento, parcelas,
                            token_pagamento, valor_total, status, id_reserva,
                            criado_em, atualizado_em
                        ) VALUES (
                            :idCompra, :idEmpresa, :idCliente, :emailCliente, :moeda,
                            :pais, :dispositivo, :metodoPagamento, :parcelas,
                            :tokenPagamento, :valorTotal, :status, :idReserva,
                            :criadoEm, :atualizadoEm
                        )
                        """)
                .param("idCompra", compra.idCompra())
                .param("idEmpresa", compra.idEmpresa())
                .param("idCliente", compra.idCliente())
                .param("emailCliente", compra.emailCliente())
                .param("moeda", compra.moeda())
                .param("pais", compra.pais())
                .param("dispositivo", compra.identificadorDispositivo())
                .param("metodoPagamento", compra.metodoPagamento().name())
                .param("parcelas", compra.parcelas())
                .param("tokenPagamento", tokenPagamento == null || tokenPagamento.isBlank()
                        ? null
                        : protecaoToken.proteger(tokenPagamento, compra.idCompra()))
                .param("valorTotal", compra.valorTotal())
                .param("status", compra.status().name())
                .param("idReserva", compra.idReserva())
                .param("criadoEm", DatasSql.gravar(compra.criadoEm()))
                .param("atualizadoEm", DatasSql.gravar(compra.atualizadoEm()))
                .update();

        for (ItemCompra item : compra.itens()) {
            banco.sql("""
                            INSERT INTO item_compra (
                                id_item, id_compra, id_produto, quantidade, preco_unitario
                            ) VALUES (
                                :idItem, :idCompra, :idProduto, :quantidade, :precoUnitario
                            )
                            """)
                    .param("idItem", UUID.randomUUID())
                    .param("idCompra", compra.idCompra())
                    .param("idProduto", item.idProduto())
                    .param("quantidade", item.quantidade())
                    .param("precoUnitario", item.precoUnitario())
                    .update();
        }

        banco.sql("""
                        INSERT INTO requisicao_idempotente (
                            id_empresa, chave, hash_requisicao, id_compra, criada_em
                        ) VALUES (:idEmpresa, :chave, :hash, :idCompra, :criadaEm)
                        """)
                .param("idEmpresa", compra.idEmpresa())
                .param("chave", chaveIdempotencia)
                .param("hash", hashRequisicao)
                .param("idCompra", compra.idCompra())
                .param("criadaEm", DatasSql.gravar(compra.criadoEm()))
                .update();
    }

    public Optional<Compra> buscar(UUID idEmpresa, UUID idCompra) {
        return banco.sql("""
                        SELECT id_compra, id_empresa, id_cliente, email_cliente,
                               moeda, pais, identificador_dispositivo,
                               metodo_pagamento, parcelas, valor_total,
                               status, id_reserva, id_pagamento, id_transacao_contabil,
                               pagamento_estornado, estoque_liberado, motivo,
                               criado_em, atualizado_em
                          FROM compra
                         WHERE id_empresa = :idEmpresa AND id_compra = :idCompra
                        """)
                .param("idEmpresa", idEmpresa)
                .param("idCompra", idCompra)
                .query((resultado, linha) -> mapearCompra(resultado, buscarItens(idCompra)))
                .optional();
    }

    public Optional<Compra> buscarParaAtualizacao(UUID idEmpresa, UUID idCompra) {
        return banco.sql("""
                        SELECT id_compra, id_empresa, id_cliente, email_cliente,
                               moeda, pais, identificador_dispositivo,
                               metodo_pagamento, parcelas, valor_total,
                               status, id_reserva, id_pagamento, id_transacao_contabil,
                               pagamento_estornado, estoque_liberado, motivo,
                               criado_em, atualizado_em
                          FROM compra
                         WHERE id_empresa = :idEmpresa AND id_compra = :idCompra
                         FOR UPDATE
                        """)
                .param("idEmpresa", idEmpresa)
                .param("idCompra", idCompra)
                .query((resultado, linha) -> mapearCompra(resultado, buscarItens(idCompra)))
                .optional();
    }

    public Optional<String> buscarTokenProtegido(UUID idEmpresa, UUID idCompra) {
        return banco.sql("""
                        SELECT token_pagamento
                          FROM compra
                         WHERE id_empresa = :idEmpresa AND id_compra = :idCompra
                        """)
                .param("idEmpresa", idEmpresa)
                .param("idCompra", idCompra)
                .query(String.class)
                .optional();
    }

    public List<Compra> buscarTravadas(
            Instant limiteRecebida,
            Instant limiteEstoqueReservado,
            Instant limiteRiscoAprovado,
            Instant limitePagamentoAutorizado,
            Instant limiteCompensando,
            int tamanhoLote) {
        return banco.sql("""
                        SELECT id_compra, id_empresa, id_cliente, email_cliente,
                               moeda, pais, identificador_dispositivo,
                               metodo_pagamento, parcelas, valor_total,
                               status, id_reserva, id_pagamento, id_transacao_contabil,
                               pagamento_estornado, estoque_liberado, motivo,
                               criado_em, atualizado_em
                          FROM compra
                         WHERE (status = 'RECEBIDA' AND atualizado_em <= :limiteRecebida)
                            OR (status = 'ESTOQUE_RESERVADO' AND atualizado_em <= :limiteEstoque)
                            OR (status = 'RISCO_APROVADO' AND atualizado_em <= :limiteRisco)
                            OR (status = 'PAGAMENTO_AUTORIZADO' AND atualizado_em <= :limitePagamento)
                            OR (status = 'COMPENSANDO' AND atualizado_em <= :limiteCompensando)
                         ORDER BY atualizado_em, id_compra
                         FOR UPDATE SKIP LOCKED
                         LIMIT :tamanhoLote
                        """)
                .param("limiteRecebida", DatasSql.gravar(limiteRecebida))
                .param("limiteEstoque", DatasSql.gravar(limiteEstoqueReservado))
                .param("limiteRisco", DatasSql.gravar(limiteRiscoAprovado))
                .param("limitePagamento", DatasSql.gravar(limitePagamentoAutorizado))
                .param("limiteCompensando", DatasSql.gravar(limiteCompensando))
                .param("tamanhoLote", tamanhoLote)
                .query((resultado, linha) -> mapearCompra(
                        resultado,
                        buscarItens(resultado.getObject("id_compra", UUID.class))))
                .list();
    }

    public void registrarReativacaoWatchdog(Compra compra, Instant agora) {
        banco.sql("""
                        UPDATE compra
                           SET atualizado_em = :agora
                         WHERE id_compra = :idCompra AND status = :status
                        """)
                .param("idCompra", compra.idCompra())
                .param("status", compra.status().name())
                .param("agora", DatasSql.gravar(agora))
                .update();
        adicionarHistorico(
                compra.idCompra(),
                "Watchdog reativou a saga",
                compra.status(),
                compra.status(),
                null,
                "Comandos idempotentes da etapa foram republicados",
                agora);
    }

    public boolean mudarStatus(
            UUID idCompra,
            StatusCompra esperado,
            StatusCompra novo,
            String motivo,
            Instant atualizadoEm) {
        return banco.sql("""
                        UPDATE compra
                           SET status = :novo,
                               motivo = :motivo,
                               atualizado_em = :atualizadoEm
                         WHERE id_compra = :idCompra AND status = :esperado
                        """)
                .param("idCompra", idCompra)
                .param("esperado", esperado.name())
                .param("novo", novo.name())
                .param("motivo", motivo)
                .param("atualizadoEm", DatasSql.gravar(atualizadoEm))
                .update() == 1;
    }

    public void vincularPagamento(UUID idCompra, UUID idPagamento, Instant atualizadoEm) {
        banco.sql("""
                        UPDATE compra
                           SET id_pagamento = :idPagamento,
                               atualizado_em = :atualizadoEm
                         WHERE id_compra = :idCompra
                        """)
                .param("idCompra", idCompra)
                .param("idPagamento", idPagamento)
                .param("atualizadoEm", DatasSql.gravar(atualizadoEm))
                .update();
    }

    public void vincularTransacaoContabil(UUID idCompra, UUID idTransacao, Instant atualizadoEm) {
        banco.sql("""
                        UPDATE compra
                           SET id_transacao_contabil = :idTransacao,
                               atualizado_em = :atualizadoEm
                         WHERE id_compra = :idCompra
                        """)
                .param("idCompra", idCompra)
                .param("idTransacao", idTransacao)
                .param("atualizadoEm", DatasSql.gravar(atualizadoEm))
                .update();
    }

    public void marcarPagamentoEstornado(UUID idCompra, Instant atualizadoEm) {
        banco.sql("""
                        UPDATE compra SET pagamento_estornado = TRUE, atualizado_em = :atualizadoEm
                         WHERE id_compra = :idCompra
                        """)
                .param("idCompra", idCompra)
                .param("atualizadoEm", DatasSql.gravar(atualizadoEm))
                .update();
    }

    public void marcarEstoqueLiberado(UUID idCompra, Instant atualizadoEm) {
        banco.sql("""
                        UPDATE compra SET estoque_liberado = TRUE, atualizado_em = :atualizadoEm
                         WHERE id_compra = :idCompra
                        """)
                .param("idCompra", idCompra)
                .param("atualizadoEm", DatasSql.gravar(atualizadoEm))
                .update();
    }

    public void adicionarHistorico(
            UUID idCompra,
            String etapa,
            StatusCompra anterior,
            StatusCompra atual,
            UUID idEvento,
            String detalhes,
            Instant registradoEm) {
        banco.sql("""
                        INSERT INTO historico_saga (
                            id_historico, id_compra, etapa, status_anterior,
                            status_atual, id_evento, detalhes, registrado_em
                        ) VALUES (
                            :idHistorico, :idCompra, :etapa, :anterior,
                            :atual, :idEvento, :detalhes, :registradoEm
                        )
                        """)
                .param("idHistorico", UUID.randomUUID())
                .param("idCompra", idCompra)
                .param("etapa", etapa)
                .param("anterior", anterior == null ? null : anterior.name())
                .param("atual", atual.name())
                .param("idEvento", idEvento)
                .param("detalhes", detalhes)
                .param("registradoEm", DatasSql.gravar(registradoEm))
                .update();
    }

    public List<RegistroHistorico> buscarHistorico(UUID idEmpresa, UUID idCompra) {
        return banco.sql("""
                        SELECT h.etapa, h.status_anterior, h.status_atual,
                               h.id_evento, h.detalhes, h.registrado_em
                          FROM historico_saga h
                          JOIN compra c ON c.id_compra = h.id_compra
                         WHERE c.id_empresa = :idEmpresa AND c.id_compra = :idCompra
                         ORDER BY h.registrado_em, h.id_historico
                        """)
                .param("idEmpresa", idEmpresa)
                .param("idCompra", idCompra)
                .query((resultado, linha) -> new RegistroHistorico(
                        resultado.getString("etapa"),
                        resultado.getString("status_anterior"),
                        resultado.getString("status_atual"),
                        resultado.getObject("id_evento", UUID.class),
                        resultado.getString("detalhes"),
                        DatasSql.ler(resultado, "registrado_em")))
                .list();
    }

    private List<ItemCompra> buscarItens(UUID idCompra) {
        return banco.sql("""
                        SELECT id_produto, quantidade, preco_unitario
                          FROM item_compra WHERE id_compra = :idCompra ORDER BY id_item
                        """)
                .param("idCompra", idCompra)
                .query((resultado, linha) -> new ItemCompra(
                        resultado.getObject("id_produto", UUID.class),
                        resultado.getInt("quantidade"),
                        resultado.getBigDecimal("preco_unitario")))
                .list();
    }

    private Compra mapearCompra(ResultSet resultado, List<ItemCompra> itens) throws SQLException {
        UUID idCompra = resultado.getObject("id_compra", UUID.class);
        return new Compra(
                idCompra,
                resultado.getObject("id_empresa", UUID.class),
                resultado.getString("id_cliente"),
                resultado.getString("email_cliente"),
                resultado.getString("moeda"),
                resultado.getString("pais"),
                resultado.getString("identificador_dispositivo"),
                br.com.orquestrapay.contracts.MetodoPagamento.valueOf(
                        resultado.getString("metodo_pagamento")),
                resultado.getInt("parcelas"),
                resultado.getBigDecimal("valor_total"),
                StatusCompra.valueOf(resultado.getString("status")),
                resultado.getObject("id_reserva", UUID.class),
                resultado.getObject("id_pagamento", UUID.class),
                resultado.getObject("id_transacao_contabil", UUID.class),
                resultado.getBoolean("pagamento_estornado"),
                resultado.getBoolean("estoque_liberado"),
                resultado.getString("motivo"),
                DatasSql.ler(resultado, "criado_em"),
                DatasSql.ler(resultado, "atualizado_em"),
                itens);
    }

    public record IdempotenciaExistente(String hashRequisicao, UUID idCompra) {
    }
}
