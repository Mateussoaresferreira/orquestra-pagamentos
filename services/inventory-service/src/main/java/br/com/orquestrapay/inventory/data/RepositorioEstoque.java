package br.com.orquestrapay.inventory.data;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import br.com.orquestrapay.contracts.ItemCompra;
import br.com.orquestrapay.inventory.api.RespostaEstoque;
import br.com.orquestrapay.inventory.domain.StatusReserva;
import br.com.orquestrapay.platform.data.DatasSql;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RepositorioEstoque {

    private final JdbcClient banco;

    public RepositorioEstoque(JdbcClient banco) {
        this.banco = banco;
    }

    public void definirSaldo(UUID idEmpresa, UUID idProduto, int quantidade, Instant agora) {
        banco.sql("""
                        INSERT INTO saldo_estoque (
                            id_empresa, id_produto, quantidade_disponivel,
                            quantidade_reservada, atualizado_em
                        ) VALUES (:idEmpresa, :idProduto, :quantidade, 0, :agora)
                        ON CONFLICT (id_empresa, id_produto) DO UPDATE
                           SET quantidade_disponivel = EXCLUDED.quantidade_disponivel,
                               atualizado_em = EXCLUDED.atualizado_em
                        """)
                .param("idEmpresa", idEmpresa)
                .param("idProduto", idProduto)
                .param("quantidade", quantidade)
                .param("agora", DatasSql.gravar(agora))
                .update();
    }

    public Optional<RespostaEstoque> buscarSaldo(UUID idEmpresa, UUID idProduto) {
        return banco.sql("""
                        SELECT id_empresa, id_produto, quantidade_disponivel,
                               quantidade_reservada, atualizado_em
                          FROM saldo_estoque
                         WHERE id_empresa = :idEmpresa AND id_produto = :idProduto
                        """)
                .param("idEmpresa", idEmpresa)
                .param("idProduto", idProduto)
                .query((resultado, linha) -> new RespostaEstoque(
                        resultado.getObject("id_empresa", UUID.class),
                        resultado.getObject("id_produto", UUID.class),
                        resultado.getInt("quantidade_disponivel"),
                        resultado.getInt("quantidade_reservada"),
                        DatasSql.ler(resultado, "atualizado_em")))
                .optional();
    }

    public List<SaldoBloqueado> bloquearSaldos(UUID idEmpresa, List<UUID> produtos) {
        return banco.sql("""
                        SELECT id_produto, quantidade_disponivel, quantidade_reservada
                          FROM saldo_estoque
                         WHERE id_empresa = :idEmpresa AND id_produto IN (:produtos)
                         ORDER BY id_produto
                         FOR UPDATE
                        """)
                .param("idEmpresa", idEmpresa)
                .param("produtos", produtos)
                .query((resultado, linha) -> new SaldoBloqueado(
                        resultado.getObject("id_produto", UUID.class),
                        resultado.getInt("quantidade_disponivel"),
                        resultado.getInt("quantidade_reservada")))
                .list();
    }

    public boolean reservaExiste(UUID idEmpresa, UUID idReserva) {
        return banco.sql("""
                        SELECT COUNT(*) FROM reserva_estoque
                         WHERE id_empresa = :idEmpresa AND id_reserva = :idReserva
                        """)
                .param("idEmpresa", idEmpresa)
                .param("idReserva", idReserva)
                .query(Integer.class)
                .single() > 0;
    }

    public void salvarReserva(
            UUID idReserva,
            UUID idEmpresa,
            UUID idCompra,
            StatusReserva status,
            String motivo,
            List<ItemCompra> itens,
            Instant agora) {
        banco.sql("""
                        INSERT INTO reserva_estoque (
                            id_reserva, id_empresa, id_compra, status,
                            motivo, criada_em, atualizada_em
                        ) VALUES (
                            :idReserva, :idEmpresa, :idCompra, :status,
                            :motivo, :agora, :agora
                        )
                        """)
                .param("idReserva", idReserva)
                .param("idEmpresa", idEmpresa)
                .param("idCompra", idCompra)
                .param("status", status.name())
                .param("motivo", motivo)
                .param("agora", DatasSql.gravar(agora))
                .update();

        for (ItemCompra item : itens) {
            banco.sql("""
                            INSERT INTO item_reserva (id_reserva, id_produto, quantidade)
                            VALUES (:idReserva, :idProduto, :quantidade)
                            """)
                    .param("idReserva", idReserva)
                    .param("idProduto", item.idProduto())
                    .param("quantidade", item.quantidade())
                    .update();
        }
    }

    public void reservar(UUID idEmpresa, ItemCompra item, Instant agora) {
        int alterados = banco.sql("""
                        UPDATE saldo_estoque
                           SET quantidade_disponivel = quantidade_disponivel - :quantidade,
                               quantidade_reservada = quantidade_reservada + :quantidade,
                               atualizado_em = :agora
                         WHERE id_empresa = :idEmpresa
                           AND id_produto = :idProduto
                           AND quantidade_disponivel >= :quantidade
                        """)
                .param("idEmpresa", idEmpresa)
                .param("idProduto", item.idProduto())
                .param("quantidade", item.quantidade())
                .param("agora", DatasSql.gravar(agora))
                .update();
        if (alterados != 1) {
            throw new IllegalStateException("O saldo mudou durante a reserva do produto " + item.idProduto());
        }
    }

    public Optional<Reserva> bloquearReserva(UUID idReserva) {
        return banco.sql("""
                        SELECT id_reserva, id_empresa, id_compra, status
                          FROM reserva_estoque
                         WHERE id_reserva = :idReserva
                         FOR UPDATE
                        """)
                .param("idReserva", idReserva)
                .query((resultado, linha) -> new Reserva(
                        resultado.getObject("id_reserva", UUID.class),
                        resultado.getObject("id_empresa", UUID.class),
                        resultado.getObject("id_compra", UUID.class),
                        StatusReserva.valueOf(resultado.getString("status"))))
                .optional();
    }

    public List<ItemReserva> buscarItensReserva(UUID idReserva) {
        return banco.sql("""
                        SELECT id_produto, quantidade FROM item_reserva
                         WHERE id_reserva = :idReserva ORDER BY id_produto
                        """)
                .param("idReserva", idReserva)
                .query((resultado, linha) -> new ItemReserva(
                        resultado.getObject("id_produto", UUID.class),
                        resultado.getInt("quantidade")))
                .list();
    }

    public void liberar(UUID idEmpresa, ItemReserva item, Instant agora) {
        int alterados = banco.sql("""
                        UPDATE saldo_estoque
                           SET quantidade_disponivel = quantidade_disponivel + :quantidade,
                               quantidade_reservada = quantidade_reservada - :quantidade,
                               atualizado_em = :agora
                         WHERE id_empresa = :idEmpresa
                           AND id_produto = :idProduto
                           AND quantidade_reservada >= :quantidade
                        """)
                .param("idEmpresa", idEmpresa)
                .param("idProduto", item.idProduto())
                .param("quantidade", item.quantidade())
                .param("agora", DatasSql.gravar(agora))
                .update();
        if (alterados != 1) {
            throw new IllegalStateException(
                    "Nao foi possivel liberar o saldo reservado do produto " + item.idProduto());
        }
    }

    public void marcarLiberada(UUID idReserva, Instant agora) {
        banco.sql("""
                        UPDATE reserva_estoque
                           SET status = 'LIBERADA', atualizada_em = :agora
                         WHERE id_reserva = :idReserva AND status = 'RESERVADA'
                        """)
                .param("idReserva", idReserva)
                .param("agora", DatasSql.gravar(agora))
                .update();
    }

    public record SaldoBloqueado(UUID idProduto, int disponivel, int reservado) {
    }

    public record Reserva(UUID idReserva, UUID idEmpresa, UUID idCompra, StatusReserva status) {
    }

    public record ItemReserva(UUID idProduto, int quantidade) {
    }
}
