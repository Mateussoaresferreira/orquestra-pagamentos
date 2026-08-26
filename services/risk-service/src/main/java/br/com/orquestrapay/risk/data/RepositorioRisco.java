package br.com.orquestrapay.risk.data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import br.com.orquestrapay.platform.data.DatasSql;
import br.com.orquestrapay.risk.api.RespostaAnaliseRisco;
import br.com.orquestrapay.risk.api.RespostaComparacaoModelosRisco;
import br.com.orquestrapay.risk.api.RespostaResumoModelosRisco;
import br.com.orquestrapay.risk.domain.ClassificacaoComparacaoRisco;
import br.com.orquestrapay.risk.domain.ResultadoAvaliacaoRisco;
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

    public void bloquearJanelasDeVelocidade(
            UUID idEmpresa,
            String idCliente,
            String identificadorDispositivo) {
        Stream.of(
                        "cliente:" + idEmpresa + ":" + idCliente,
                        "dispositivo:" + idEmpresa + ":" + identificadorDispositivo)
                .sorted()
                .forEach(chave -> banco.sql(
                                "SELECT pg_advisory_xact_lock(hashtextextended(:chave, 0))")
                        .param("chave", chave)
                        .query((resultado, linha) -> Boolean.TRUE)
                        .single());
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
            String modeloDecisao,
            String versaoModeloDecisao,
            Instant agora) {
        UUID idAnalise = UUID.randomUUID();
        banco.sql("""
                        INSERT INTO analise_risco (
                            id_analise, id_empresa, id_compra, id_cliente,
                            identificador_dispositivo, valor_total, pais,
                            pontuacao, aprovada, sinais,
                            modelo_decisao, versao_modelo_decisao, analisada_em
                        ) VALUES (
                            :idAnalise, :idEmpresa, :idCompra, :idCliente,
                            :dispositivo, :valor, :pais,
                            :pontuacao, :aprovada, :sinais,
                            :modeloDecisao, :versaoModeloDecisao, :agora
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
                .param("modeloDecisao", modeloDecisao)
                .param("versaoModeloDecisao", versaoModeloDecisao)
                .param("agora", DatasSql.gravar(agora))
                .update();
        return idAnalise;
    }

    public Optional<RespostaAnaliseRisco> buscar(UUID idEmpresa, UUID idCompra) {
        return banco.sql("""
                        SELECT id_analise, id_compra, pontuacao,
                               aprovada, sinais, modelo_decisao,
                               versao_modelo_decisao, analisada_em
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
                        resultado.getString("modelo_decisao"),
                        resultado.getString("versao_modelo_decisao"),
                        DatasSql.ler(resultado, "analisada_em")))
                .optional();
    }

    public boolean adicionarComparacao(
            UUID idEmpresa,
            UUID idCompra,
            ResultadoAvaliacaoRisco champion,
            ResultadoAvaliacaoRisco challenger,
            ClassificacaoComparacaoRisco classificacao,
            Instant agora) {
        return banco.sql("""
                        INSERT INTO comparacao_modelos_risco (
                            id_comparacao, id_empresa, id_compra,
                            modelo_champion, versao_champion,
                            pontuacao_champion, aprovada_champion, sinais_champion,
                            modelo_challenger, versao_challenger,
                            pontuacao_challenger, aprovada_challenger, sinais_challenger,
                            classificacao, diferenca_pontuacao, avaliada_em
                        ) VALUES (
                            :idComparacao, :idEmpresa, :idCompra,
                            :modeloChampion, :versaoChampion,
                            :pontuacaoChampion, :aprovadaChampion, :sinaisChampion,
                            :modeloChallenger, :versaoChallenger,
                            :pontuacaoChallenger, :aprovadaChallenger, :sinaisChallenger,
                            :classificacao, :diferencaPontuacao, :agora
                        )
                        ON CONFLICT (
                            id_empresa, id_compra, modelo_challenger, versao_challenger
                        ) DO NOTHING
                        """)
                .param("idComparacao", UUID.randomUUID())
                .param("idEmpresa", idEmpresa)
                .param("idCompra", idCompra)
                .param("modeloChampion", champion.modelo())
                .param("versaoChampion", champion.versao())
                .param("pontuacaoChampion", champion.pontuacao())
                .param("aprovadaChampion", champion.aprovada())
                .param("sinaisChampion", champion.descricao())
                .param("modeloChallenger", challenger.modelo())
                .param("versaoChallenger", challenger.versao())
                .param("pontuacaoChallenger", challenger.pontuacao())
                .param("aprovadaChallenger", challenger.aprovada())
                .param("sinaisChallenger", challenger.descricao())
                .param("classificacao", classificacao.name())
                .param("diferencaPontuacao", challenger.pontuacao() - champion.pontuacao())
                .param("agora", DatasSql.gravar(agora))
                .update() == 1;
    }

    public Optional<RespostaComparacaoModelosRisco> buscarComparacao(
            UUID idEmpresa,
            UUID idCompra) {
        return banco.sql("""
                        SELECT id_comparacao, id_compra,
                               modelo_champion, versao_champion,
                               pontuacao_champion, aprovada_champion, sinais_champion,
                               modelo_challenger, versao_challenger,
                               pontuacao_challenger, aprovada_challenger, sinais_challenger,
                               classificacao, diferenca_pontuacao, avaliada_em
                          FROM comparacao_modelos_risco
                         WHERE id_empresa = :idEmpresa AND id_compra = :idCompra
                         ORDER BY avaliada_em DESC
                         LIMIT 1
                        """)
                .param("idEmpresa", idEmpresa)
                .param("idCompra", idCompra)
                .query((resultado, linha) -> new RespostaComparacaoModelosRisco(
                        resultado.getObject("id_comparacao", UUID.class),
                        resultado.getObject("id_compra", UUID.class),
                        resultado.getString("modelo_champion"),
                        resultado.getString("versao_champion"),
                        resultado.getInt("pontuacao_champion"),
                        resultado.getBoolean("aprovada_champion"),
                        resultado.getString("sinais_champion"),
                        resultado.getString("modelo_challenger"),
                        resultado.getString("versao_challenger"),
                        resultado.getInt("pontuacao_challenger"),
                        resultado.getBoolean("aprovada_challenger"),
                        resultado.getString("sinais_challenger"),
                        ClassificacaoComparacaoRisco.valueOf(resultado.getString("classificacao")),
                        resultado.getInt("diferenca_pontuacao"),
                        DatasSql.ler(resultado, "avaliada_em")))
                .optional();
    }

    public RespostaResumoModelosRisco resumirComparacoes(
            UUID idEmpresa,
            Instant desde,
            Instant ate) {
        return banco.sql("""
                        SELECT COUNT(*) AS total,
                               COUNT(*) FILTER (
                                   WHERE classificacao = 'DECISAO_CONCORDANTE'
                               ) AS concordantes,
                               COUNT(*) FILTER (
                                   WHERE classificacao = 'CHALLENGER_MAIS_RESTRITIVO'
                               ) AS mais_restritivo,
                               COUNT(*) FILTER (
                                   WHERE classificacao = 'CHALLENGER_MAIS_PERMISSIVO'
                               ) AS mais_permissivo,
                               COALESCE(AVG(diferenca_pontuacao), 0) AS media_diferenca
                          FROM comparacao_modelos_risco
                         WHERE id_empresa = :idEmpresa
                           AND avaliada_em >= :desde
                           AND avaliada_em < :ate
                        """)
                .param("idEmpresa", idEmpresa)
                .param("desde", DatasSql.gravar(desde))
                .param("ate", DatasSql.gravar(ate))
                .query((resultado, linha) -> new RespostaResumoModelosRisco(
                        desde,
                        ate,
                        resultado.getLong("total"),
                        resultado.getLong("concordantes"),
                        resultado.getLong("mais_restritivo"),
                        resultado.getLong("mais_permissivo"),
                        resultado.getBigDecimal("media_diferenca")))
                .single();
    }
}
