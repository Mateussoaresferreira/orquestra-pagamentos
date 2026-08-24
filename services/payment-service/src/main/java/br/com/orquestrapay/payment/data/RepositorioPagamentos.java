package br.com.orquestrapay.payment.data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import br.com.orquestrapay.platform.data.DatasSql;
import br.com.orquestrapay.payment.api.RespostaPagamento;
import br.com.orquestrapay.payment.domain.StatusPagamento;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RepositorioPagamentos {

    private final JdbcClient banco;

    public RepositorioPagamentos(JdbcClient banco) {
        this.banco = banco;
    }

    public boolean existePorCompra(UUID idEmpresa, UUID idCompra) {
        return banco.sql("""
                        SELECT COUNT(*) FROM pagamento
                         WHERE id_empresa = :idEmpresa AND id_compra = :idCompra
                        """)
                .param("idEmpresa", idEmpresa)
                .param("idCompra", idCompra)
                .query(Integer.class)
                .single() > 0;
    }

    public void adicionar(
            UUID idPagamento,
            UUID idEmpresa,
            UUID idCompra,
            BigDecimal valor,
            String moeda,
            String impressaoToken,
            StatusPagamento status,
            String idAutorizacao,
            String motivo,
            Instant agora) {
        banco.sql("""
                        INSERT INTO pagamento (
                            id_pagamento, id_empresa, id_compra, valor, moeda,
                            impressao_token, status, id_autorizacao, motivo,
                            criado_em, atualizado_em
                        ) VALUES (
                            :idPagamento, :idEmpresa, :idCompra, :valor, :moeda,
                            :impressaoToken, :status, :idAutorizacao, :motivo,
                            :agora, :agora
                        )
                        """)
                .param("idPagamento", idPagamento)
                .param("idEmpresa", idEmpresa)
                .param("idCompra", idCompra)
                .param("valor", valor)
                .param("moeda", moeda)
                .param("impressaoToken", impressaoToken)
                .param("status", status.name())
                .param("idAutorizacao", idAutorizacao)
                .param("motivo", motivo)
                .param("agora", DatasSql.gravar(agora))
                .update();
        registrarTentativa(idPagamento, "AUTORIZACAO", status.name(), motivo, agora);
    }

    public Optional<Pagamento> bloquear(UUID idPagamento) {
        return banco.sql("""
                        SELECT id_pagamento, id_empresa, id_compra, status
                          FROM pagamento WHERE id_pagamento = :idPagamento FOR UPDATE
                        """)
                .param("idPagamento", idPagamento)
                .query((resultado, linha) -> new Pagamento(
                        resultado.getObject("id_pagamento", UUID.class),
                        resultado.getObject("id_empresa", UUID.class),
                        resultado.getObject("id_compra", UUID.class),
                        StatusPagamento.valueOf(resultado.getString("status"))))
                .optional();
    }

    public void marcarEstornado(UUID idPagamento, String protocolo, Instant agora) {
        banco.sql("""
                        UPDATE pagamento
                           SET status = 'ESTORNADO', motivo = :motivo, atualizado_em = :agora
                         WHERE id_pagamento = :idPagamento AND status = 'AUTORIZADO'
                        """)
                .param("idPagamento", idPagamento)
                .param("motivo", "Estorno confirmado: " + protocolo)
                .param("agora", DatasSql.gravar(agora))
                .update();
        registrarTentativa(idPagamento, "ESTORNO", "ESTORNADO", protocolo, agora);
    }

    public Optional<RespostaPagamento> buscar(UUID idEmpresa, UUID idCompra) {
        return banco.sql("""
                        SELECT id_pagamento, id_compra, valor, moeda,
                               status, id_autorizacao, motivo, atualizado_em
                          FROM pagamento
                         WHERE id_empresa = :idEmpresa AND id_compra = :idCompra
                        """)
                .param("idEmpresa", idEmpresa)
                .param("idCompra", idCompra)
                .query((resultado, linha) -> new RespostaPagamento(
                        resultado.getObject("id_pagamento", UUID.class),
                        resultado.getObject("id_compra", UUID.class),
                        resultado.getBigDecimal("valor"),
                        resultado.getString("moeda"),
                        resultado.getString("status"),
                        resultado.getString("id_autorizacao"),
                        resultado.getString("motivo"),
                        DatasSql.ler(resultado, "atualizado_em")))
                .optional();
    }

    public Map<UUID, RespostaPagamento> buscarPorPagamentos(UUID idEmpresa, List<UUID> idsPagamentos) {
        if (idsPagamentos.isEmpty()) {
            return Map.of();
        }

        return banco.sql("""
                        SELECT id_pagamento, id_compra, valor, moeda,
                               status, id_autorizacao, motivo, atualizado_em
                          FROM pagamento
                         WHERE id_empresa = :idEmpresa AND id_pagamento IN (:idsPagamentos)
                        """)
                .param("idEmpresa", idEmpresa)
                .param("idsPagamentos", idsPagamentos)
                .query((resultado, linha) -> new RespostaPagamento(
                        resultado.getObject("id_pagamento", UUID.class),
                        resultado.getObject("id_compra", UUID.class),
                        resultado.getBigDecimal("valor"),
                        resultado.getString("moeda"),
                        resultado.getString("status"),
                        resultado.getString("id_autorizacao"),
                        resultado.getString("motivo"),
                        DatasSql.ler(resultado, "atualizado_em")))
                .list()
                .stream()
                .collect(Collectors.toUnmodifiableMap(
                        RespostaPagamento::idPagamento,
                        Function.identity()));
    }

    public void registrarDivergencia(
            UUID idEmpresa,
            UUID idPagamento,
            String tipo,
            String detalhes,
            Instant agora) {
        banco.sql("""
                        INSERT INTO divergencia_conciliacao (
                            id_divergencia, id_empresa, id_pagamento,
                            tipo, detalhes, identificada_em
                        ) VALUES (
                            :id, :idEmpresa, :idPagamento, :tipo, :detalhes, :agora
                        )
                        """)
                .param("id", UUID.randomUUID())
                .param("idEmpresa", idEmpresa)
                .param("idPagamento", idPagamento)
                .param("tipo", tipo)
                .param("detalhes", detalhes)
                .param("agora", DatasSql.gravar(agora))
                .update();
    }

    private void registrarTentativa(
            UUID idPagamento,
            String operacao,
            String resultado,
            String detalhes,
            Instant agora) {
        banco.sql("""
                        INSERT INTO tentativa_pagamento (
                            id_tentativa, id_pagamento, operacao,
                            resultado, detalhes, realizada_em
                        ) VALUES (:id, :idPagamento, :operacao, :resultado, :detalhes, :agora)
                        """)
                .param("id", UUID.randomUUID())
                .param("idPagamento", idPagamento)
                .param("operacao", operacao)
                .param("resultado", resultado)
                .param("detalhes", detalhes)
                .param("agora", DatasSql.gravar(agora))
                .update();
    }

    public record Pagamento(
            UUID idPagamento,
            UUID idEmpresa,
            UUID idCompra,
            StatusPagamento status) {
    }
}
