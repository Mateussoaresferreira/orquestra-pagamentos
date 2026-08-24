package br.com.orquestrapay.risk.data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import br.com.orquestrapay.platform.data.DatasSql;
import br.com.orquestrapay.risk.api.RespostaAnaliseRisco;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RepositorioRisco {

    private final JdbcClient banco;

    public RepositorioRisco(JdbcClient banco) {
        this.banco = banco;
    }

    public boolean existePorCompra(UUID idEmpresa, UUID idCompra) {
        return banco.sql("""
                        SELECT COUNT(*) FROM analise_risco
                         WHERE id_empresa = :idEmpresa AND id_compra = :idCompra
                        """)
                .param("idEmpresa", idEmpresa)
                .param("idCompra", idCompra)
                .query(Integer.class)
                .single() > 0;
    }

    public int contarComprasRecentes(UUID idEmpresa, String idCliente, Instant desde) {
        return banco.sql("""
                        SELECT COUNT(*) FROM analise_risco
                         WHERE id_empresa = :idEmpresa
                           AND id_cliente = :idCliente
                           AND analisada_em >= :desde
                        """)
                .param("idEmpresa", idEmpresa)
                .param("idCliente", idCliente)
                .param("desde", DatasSql.gravar(desde))
                .query(Integer.class)
                .single();
    }

    public int contarClientesNoDispositivo(
            UUID idEmpresa,
            String dispositivo,
            String idClienteAtual,
            Instant desde) {
        return banco.sql("""
                        SELECT COUNT(DISTINCT id_cliente) FROM analise_risco
                         WHERE id_empresa = :idEmpresa
                           AND identificador_dispositivo = :dispositivo
                           AND id_cliente <> :idClienteAtual
                           AND analisada_em >= :desde
                        """)
                .param("idEmpresa", idEmpresa)
                .param("dispositivo", dispositivo)
                .param("idClienteAtual", idClienteAtual)
                .param("desde", DatasSql.gravar(desde))
                .query(Integer.class)
                .single();
    }

    public UUID adicionar(
            UUID idEmpresa,
            UUID idCompra,
            String idCliente,
            String dispositivo,
            BigDecimal valor,
            String pais,
            int pontuacao,
            boolean aprovada,
            String sinais,
            Instant agora) {
        UUID idAnalise = UUID.randomUUID();
        banco.sql("""
                        INSERT INTO analise_risco (
                            id_analise, id_empresa, id_compra, id_cliente,
                            identificador_dispositivo, valor_total, pais,
                            pontuacao, aprovada, sinais, analisada_em
                        ) VALUES (
                            :idAnalise, :idEmpresa, :idCompra, :idCliente,
                            :dispositivo, :valor, :pais,
                            :pontuacao, :aprovada, :sinais, :agora
                        )
                        """)
                .param("idAnalise", idAnalise)
                .param("idEmpresa", idEmpresa)
                .param("idCompra", idCompra)
                .param("idCliente", idCliente)
                .param("dispositivo", dispositivo)
                .param("valor", valor)
                .param("pais", pais)
                .param("pontuacao", pontuacao)
                .param("aprovada", aprovada)
                .param("sinais", sinais)
                .param("agora", DatasSql.gravar(agora))
                .update();
        return idAnalise;
    }

    public Optional<RespostaAnaliseRisco> buscar(UUID idEmpresa, UUID idCompra) {
        return banco.sql("""
                        SELECT id_analise, id_compra, pontuacao,
                               aprovada, sinais, analisada_em
                          FROM analise_risco
                         WHERE id_empresa = :idEmpresa AND id_compra = :idCompra
                        """)
                .param("idEmpresa", idEmpresa)
                .param("idCompra", idCompra)
                .query((resultado, linha) -> new RespostaAnaliseRisco(
                        resultado.getObject("id_analise", UUID.class),
                        resultado.getObject("id_compra", UUID.class),
                        resultado.getInt("pontuacao"),
                        resultado.getBoolean("aprovada"),
                        resultado.getString("sinais"),
                        DatasSql.ler(resultado, "analisada_em")))
                .optional();
    }
}
