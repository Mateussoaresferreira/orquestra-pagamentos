package br.com.orquestrapay.payment.data;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import br.com.orquestrapay.contracts.MetodoPagamento;
import br.com.orquestrapay.payment.api.RespostaPagamento;
import br.com.orquestrapay.payment.api.PaginaDivergencias;
import br.com.orquestrapay.payment.api.RespostaConciliacaoResumo;
import br.com.orquestrapay.payment.api.RespostaDivergencia;
import br.com.orquestrapay.payment.domain.StatusPagamento;
import br.com.orquestrapay.platform.data.DatasSql;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RepositorioPagamentos {

    private static final String COLUNAS_RESPOSTA = """
            id_pagamento, id_compra, valor, moeda, status,
            id_autorizacao, motivo, metodo_pagamento, parcelas,
            provedor, txid, copia_cola_pix, imagem_qr_code_base64,
            expira_em, atualizado_em
            """;

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

    public UUID adicionarPendente(
            UUID idEmpresa,
            UUID idCompra,
            BigDecimal valor,
            String moeda,
            String tokenProtegido,
            MetodoPagamento metodo,
            int parcelas,
            Instant agora) {
        UUID idPagamento = UUID.randomUUID();
        banco.sql("""
                        INSERT INTO pagamento (
                            id_pagamento, id_empresa, id_compra, valor, moeda,
                            impressao_token, status, motivo, metodo_pagamento,
                            parcelas, token_protegido, criado_em, atualizado_em
                        ) VALUES (
                            :idPagamento, :idEmpresa, :idCompra, :valor, :moeda,
                            NULL, 'PENDENTE', 'Operacao recebida', :metodo,
                            :parcelas, :tokenProtegido, :agora, :agora
                        )
                        ON CONFLICT (id_compra) DO NOTHING
                        """)
                .param("idPagamento", idPagamento)
                .param("idEmpresa", idEmpresa)
                .param("idCompra", idCompra)
                .param("valor", valor)
                .param("moeda", moeda)
                .param("metodo", metodo.name())
                .param("parcelas", parcelas)
                .param("tokenProtegido", tokenProtegido)
                .param("agora", DatasSql.gravar(agora))
                .update();
        return banco.sql("""
                        SELECT id_pagamento
                          FROM pagamento
                         WHERE id_empresa = :idEmpresa AND id_compra = :idCompra
                        """)
                .param("idEmpresa", idEmpresa)
                .param("idCompra", idCompra)
                .query(UUID.class)
                .single();
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
                            metodo_pagamento, parcelas, criado_em, atualizado_em
                        ) VALUES (
                            :idPagamento, :idEmpresa, :idCompra, :valor, :moeda,
                            :impressaoToken, :status, :idAutorizacao, :motivo,
                            'CARTAO', 1, :agora, :agora
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

    public void marcarProcessando(UUID idPagamento, StatusPagamento status, Instant agora) {
        banco.sql("""
                        UPDATE pagamento
                           SET status = :status, atualizado_em = :agora
                         WHERE id_pagamento = :idPagamento
                           AND status IN (
                               'PENDENTE', 'CONFIRMACAO_PENDENTE', 'ESTORNO_PENDENTE',
                               'PROCESSANDO', 'ESTORNANDO'
                           )
                        """)
                .param("status", status.name())
                .param("agora", DatasSql.gravar(agora))
                .param("idPagamento", idPagamento)
                .update();
    }

    public void concluirAutorizacao(
            UUID idPagamento,
            StatusPagamento status,
            String provedor,
            String impressaoToken,
            String idAutorizacao,
            String motivo,
            Instant agora) {
        banco.sql("""
                        UPDATE pagamento
                           SET status = :status,
                               provedor = :provedor,
                               impressao_token = :impressaoToken,
                               token_protegido = NULL,
                               id_autorizacao = :idAutorizacao,
                               motivo = :motivo,
                               atualizado_em = :agora
                         WHERE id_pagamento = :idPagamento
                           AND status IN ('PENDENTE', 'PROCESSANDO', 'CONFIRMACAO_PENDENTE')
                        """)
                .param("status", status.name())
                .param("provedor", provedor)
                .param("impressaoToken", impressaoToken)
                .param("idAutorizacao", idAutorizacao)
                .param("motivo", motivo)
                .param("agora", DatasSql.gravar(agora))
                .param("idPagamento", idPagamento)
                .update();
        registrarTentativa(idPagamento, "AUTORIZACAO", status.name(), motivo, agora);
    }

    public void concluirCriacaoPix(
            UUID idPagamento,
            String provedor,
            String txid,
            String copiaCola,
            String imagemQrCodeBase64,
            Instant expiraEm,
            Instant agora) {
        banco.sql("""
                        UPDATE pagamento
                           SET status = 'AGUARDANDO_CONFIRMACAO',
                               provedor = :provedor,
                               txid = :txid,
                               copia_cola_pix = :copiaCola,
                               imagem_qr_code_base64 = :imagemQrCodeBase64,
                               expira_em = :expiraEm,
                               motivo = 'Aguardando confirmacao PIX',
                               atualizado_em = :agora
                         WHERE id_pagamento = :idPagamento
                           AND status IN ('PENDENTE', 'PROCESSANDO')
                        """)
                .param("provedor", provedor)
                .param("txid", txid)
                .param("copiaCola", copiaCola)
                .param("imagemQrCodeBase64", imagemQrCodeBase64)
                .param("expiraEm", DatasSql.gravar(expiraEm))
                .param("agora", DatasSql.gravar(agora))
                .param("idPagamento", idPagamento)
                .update();
        registrarTentativa(
                idPagamento,
                "CRIACAO_PIX",
                "AGUARDANDO_CONFIRMACAO",
                "Cobranca PIX criada em " + provedor,
                agora);
    }

    public Optional<Pagamento> bloquear(UUID idPagamento) {
        return banco.sql("""
                        SELECT id_pagamento, id_empresa, id_compra, status,
                               metodo_pagamento, provedor, txid
                          FROM pagamento
                         WHERE id_pagamento = :idPagamento
                         FOR UPDATE
                        """)
                .param("idPagamento", idPagamento)
                .query(this::mapearPagamento)
                .optional();
    }

    public Optional<Pagamento> bloquearPorPix(String provedor, String txid) {
        return banco.sql("""
                        SELECT id_pagamento, id_empresa, id_compra, status,
                               metodo_pagamento, provedor, txid
                          FROM pagamento
                         WHERE provedor = :provedor AND txid = :txid
                         FOR UPDATE
                        """)
                .param("provedor", provedor)
                .param("txid", txid)
                .query(this::mapearPagamento)
                .optional();
    }

    public List<Pagamento> bloquearPixExpirados(Instant agora, int limite) {
        return banco.sql("""
                        SELECT id_pagamento, id_empresa, id_compra, status,
                               metodo_pagamento, provedor, txid
                          FROM pagamento
                         WHERE status = 'AGUARDANDO_CONFIRMACAO'
                           AND expira_em <= :agora
                         ORDER BY expira_em
                         FOR UPDATE SKIP LOCKED
                         LIMIT :limite
                        """)
                .param("agora", DatasSql.gravar(agora))
                .param("limite", limite)
                .query(this::mapearPagamento)
                .list();
    }

    public boolean confirmarPix(UUID idPagamento, String idAutorizacao, Instant agora) {
        int atualizados = banco.sql("""
                        UPDATE pagamento
                           SET status = 'AUTORIZADO',
                               id_autorizacao = :idAutorizacao,
                               motivo = 'PIX confirmado pelo provedor',
                               atualizado_em = :agora
                         WHERE id_pagamento = :idPagamento
                           AND status = 'AGUARDANDO_CONFIRMACAO'
                        """)
                .param("idAutorizacao", idAutorizacao)
                .param("agora", DatasSql.gravar(agora))
                .param("idPagamento", idPagamento)
                .update();
        if (atualizados == 1) {
            registrarTentativa(idPagamento, "CONFIRMACAO_PIX", "AUTORIZADO", idAutorizacao, agora);
        }
        return atualizados == 1;
    }

    public boolean expirarPix(UUID idPagamento, String motivo, Instant agora) {
        int atualizados = banco.sql("""
                        UPDATE pagamento
                           SET status = 'EXPIRADO', motivo = :motivo, atualizado_em = :agora
                         WHERE id_pagamento = :idPagamento
                           AND status = 'AGUARDANDO_CONFIRMACAO'
                        """)
                .param("motivo", motivo)
                .param("agora", DatasSql.gravar(agora))
                .param("idPagamento", idPagamento)
                .update();
        if (atualizados == 1) {
            registrarTentativa(idPagamento, "CONFIRMACAO_PIX", "EXPIRADO", motivo, agora);
        }
        return atualizados == 1;
    }

    public boolean agendarEstornoPixConfirmadoAposExpiracao(
            UUID idPagamento,
            String idAutorizacao,
            Instant agora) {
        String motivo = "PIX confirmado apos a expiracao; devolucao automatica pendente";
        int atualizados = banco.sql("""
                        UPDATE pagamento
                           SET status = 'ESTORNO_PENDENTE',
                               id_autorizacao = :idAutorizacao,
                               motivo = :motivo,
                               atualizado_em = :agora
                         WHERE id_pagamento = :idPagamento
                           AND status = 'EXPIRADO'
                        """)
                .param("idAutorizacao", idAutorizacao)
                .param("motivo", motivo)
                .param("agora", DatasSql.gravar(agora))
                .param("idPagamento", idPagamento)
                .update();
        if (atualizados == 1) {
            registrarTentativa(
                    idPagamento,
                    "CONFIRMACAO_PIX",
                    "CONFIRMADO_APOS_EXPIRACAO",
                    motivo,
                    agora);
        }
        return atualizados == 1;
    }

    public boolean marcarEstornoPendente(UUID idPagamento, Instant agora) {
        return banco.sql("""
                        UPDATE pagamento
                           SET status = 'ESTORNO_PENDENTE',
                               motivo = 'Estorno aguardando processamento',
                               atualizado_em = :agora
                         WHERE id_pagamento = :idPagamento AND status = 'AUTORIZADO'
                        """)
                .param("idPagamento", idPagamento)
                .param("agora", DatasSql.gravar(agora))
                .update() == 1;
    }

    public boolean marcarEstornado(UUID idPagamento, String protocolo, Instant agora) {
        int atualizados = banco.sql("""
                        UPDATE pagamento
                           SET status = 'ESTORNADO', motivo = :motivo, atualizado_em = :agora
                         WHERE id_pagamento = :idPagamento
                           AND status IN ('AUTORIZADO', 'ESTORNO_PENDENTE', 'ESTORNANDO', 'EXPIRADO')
                        """)
                .param("idPagamento", idPagamento)
                .param("motivo", "Estorno confirmado: " + protocolo)
                .param("agora", DatasSql.gravar(agora))
                .update();
        if (atualizados == 1) {
            registrarTentativa(idPagamento, "ESTORNO", "ESTORNADO", protocolo, agora);
        }
        return atualizados == 1;
    }

    public void marcarFalhaTecnica(UUID idPagamento, String motivo, Instant agora) {
        banco.sql("""
                        UPDATE pagamento
                           SET status = 'FALHA_TECNICA',
                               token_protegido = NULL,
                               motivo = :motivo,
                               atualizado_em = :agora
                         WHERE id_pagamento = :idPagamento
                           AND status IN ('PENDENTE', 'PROCESSANDO', 'ESTORNO_PENDENTE', 'ESTORNANDO')
                        """)
                .param("motivo", limitar(motivo, 2_000))
                .param("agora", DatasSql.gravar(agora))
                .param("idPagamento", idPagamento)
                .update();
    }

    public void marcarConfirmacaoPendente(
            UUID idPagamento,
            String provedor,
            String motivo,
            Instant agora) {
        banco.sql("""
                        UPDATE pagamento
                           SET status = 'CONFIRMACAO_PENDENTE',
                               provedor = :provedor,
                               motivo = :motivo,
                               atualizado_em = :agora
                         WHERE id_pagamento = :idPagamento
                           AND status IN ('PENDENTE', 'PROCESSANDO', 'CONFIRMACAO_PENDENTE')
                        """)
                .param("provedor", provedor)
                .param("motivo", limitar(motivo, 2_000))
                .param("agora", DatasSql.gravar(agora))
                .param("idPagamento", idPagamento)
                .update();
    }

    public void marcarAguardandoNovaTentativa(
            UUID idPagamento,
            boolean estorno,
            String motivo,
            Instant agora) {
        banco.sql("""
                        UPDATE pagamento
                           SET status = :status,
                               motivo = :motivo,
                               atualizado_em = :agora
                         WHERE id_pagamento = :idPagamento
                           AND status IN ('PROCESSANDO', 'ESTORNANDO')
                        """)
                .param("status", estorno ? "ESTORNO_PENDENTE" : "PENDENTE")
                .param("motivo", limitar(motivo, 2_000))
                .param("agora", DatasSql.gravar(agora))
                .param("idPagamento", idPagamento)
                .update();
    }

    public Optional<RespostaPagamento> buscar(UUID idEmpresa, UUID idCompra) {
        return banco.sql("SELECT " + COLUNAS_RESPOSTA + " FROM pagamento "
                        + "WHERE id_empresa = :idEmpresa AND id_compra = :idCompra")
                .param("idEmpresa", idEmpresa)
                .param("idCompra", idCompra)
                .query(this::mapearResposta)
                .optional();
    }

    public Map<UUID, RespostaPagamento> buscarPorPagamentos(UUID idEmpresa, List<UUID> idsPagamentos) {
        if (idsPagamentos.isEmpty()) {
            return Map.of();
        }

        return banco.sql("SELECT " + COLUNAS_RESPOSTA + " FROM pagamento "
                        + "WHERE id_empresa = :idEmpresa AND id_pagamento IN (:idsPagamentos)")
                .param("idEmpresa", idEmpresa)
                .param("idsPagamentos", idsPagamentos)
                .query(this::mapearResposta)
                .list()
                .stream()
                .collect(Collectors.toUnmodifiableMap(
                        RespostaPagamento::idPagamento,
                        Function.identity()));
    }

    public Map<UUID, PagamentoConciliavel> buscarConciliaveisPorIds(
            UUID idEmpresa,
            List<UUID> idsPagamentos) {
        if (idsPagamentos.isEmpty()) {
            return Map.of();
        }

        return banco.sql("""
                        SELECT id_pagamento, valor, moeda, status,
                               id_autorizacao, provedor, criado_em
                          FROM pagamento
                         WHERE id_empresa = :idEmpresa
                           AND id_pagamento IN (:idsPagamentos)
                        """)
                .param("idEmpresa", idEmpresa)
                .param("idsPagamentos", idsPagamentos)
                .query(this::mapearPagamentoConciliavel)
                .list()
                .stream()
                .collect(Collectors.toUnmodifiableMap(
                        PagamentoConciliavel::idPagamento,
                        Function.identity()));
    }

    public List<PagamentoConciliavel> buscarConciliaveisNaJanela(
            UUID idEmpresa,
            String provedor,
            String moeda,
            Instant periodoInicio,
            Instant periodoFim,
            int limite) {
        return banco.sql("""
                        SELECT id_pagamento, valor, moeda, status,
                               id_autorizacao, provedor, criado_em
                          FROM pagamento
                         WHERE id_empresa = :idEmpresa
                           AND provedor = :provedor
                           AND moeda = :moeda
                           AND criado_em >= :periodoInicio
                           AND criado_em < :periodoFim
                           AND status IN ('AUTORIZADO', 'RECUSADO', 'ESTORNADO')
                         ORDER BY criado_em, id_pagamento
                         LIMIT :limite
                        """)
                .param("idEmpresa", idEmpresa)
                .param("provedor", provedor)
                .param("moeda", moeda)
                .param("periodoInicio", DatasSql.gravar(periodoInicio))
                .param("periodoFim", DatasSql.gravar(periodoFim))
                .param("limite", limite)
                .query(this::mapearPagamentoConciliavel)
                .list();
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
                            tipo, detalhes, status, identificada_em, atualizado_em
                        ) VALUES (
                            :id, :idEmpresa, :idPagamento,
                            :tipo, :detalhes, 'ABERTA', :agora, :agora
                        )
                        ON CONFLICT (id_empresa, id_pagamento, tipo)
                            WHERE status IN ('ABERTA', 'INVESTIGANDO')
                        DO UPDATE SET
                            detalhes = EXCLUDED.detalhes,
                            atualizado_em = EXCLUDED.atualizado_em
                        """)
                .param("id", UUID.randomUUID())
                .param("idEmpresa", idEmpresa)
                .param("idPagamento", idPagamento)
                .param("tipo", tipo)
                .param("detalhes", detalhes)
                .param("agora", DatasSql.gravar(agora))
                .update();
    }

    public void registrarTentativa(
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
                .param("detalhes", limitar(detalhes, 2_000))
                .param("agora", DatasSql.gravar(agora))
                .update();
    }

    public UUID registrarConciliacao(
            UUID idEmpresa,
            int registrosAnalisados,
            int divergenciasEncontradas,
            Instant iniciadaEm,
            Instant concluidaEm) {
        UUID idConciliacao = UUID.randomUUID();
        banco.sql("""
                        INSERT INTO conciliacao (
                            id_conciliacao, id_empresa, registros_analisados,
                            divergencias_encontradas, status, iniciada_em, concluida_em
                        ) VALUES (
                            :idConciliacao, :idEmpresa, :registros,
                            :divergencias, :status, :iniciadaEm, :concluidaEm
                        )
                        """)
                .param("idConciliacao", idConciliacao)
                .param("idEmpresa", idEmpresa)
                .param("registros", registrosAnalisados)
                .param("divergencias", divergenciasEncontradas)
                .param("status", divergenciasEncontradas == 0
                        ? "CONCLUIDA"
                        : "CONCLUIDA_COM_DIVERGENCIAS")
                .param("iniciadaEm", DatasSql.gravar(iniciadaEm))
                .param("concluidaEm", DatasSql.gravar(concluidaEm))
                .update();
        return idConciliacao;
    }

    public InicioConciliacao iniciarConciliacao(
            UUID idEmpresa,
            String provedor,
            String identificadorExtrato,
            String hashExtrato,
            String moeda,
            Instant periodoInicio,
            Instant periodoFim,
            int registrosProvedor,
            Instant iniciadaEm) {
        UUID idConciliacao = UUID.randomUUID();
        int inseridos = banco.sql("""
                        INSERT INTO conciliacao (
                            id_conciliacao, id_empresa, provedor,
                            identificador_extrato, hash_extrato, moeda,
                            periodo_inicio, periodo_fim, registros_provedor,
                            registros_locais, registros_duplicados,
                            registros_analisados, divergencias_encontradas,
                            status, iniciada_em, concluida_em
                        ) VALUES (
                            :idConciliacao, :idEmpresa, :provedor,
                            :identificadorExtrato, :hashExtrato, :moeda,
                            :periodoInicio, :periodoFim, :registrosProvedor,
                            0, 0, 0, 0,
                            'PROCESSANDO', :iniciadaEm, NULL
                        )
                        ON CONFLICT (id_empresa, provedor, identificador_extrato)
                            WHERE provedor IS NOT NULL AND identificador_extrato IS NOT NULL
                        DO NOTHING
                        """)
                .param("idConciliacao", idConciliacao)
                .param("idEmpresa", idEmpresa)
                .param("provedor", provedor)
                .param("identificadorExtrato", identificadorExtrato)
                .param("hashExtrato", hashExtrato)
                .param("moeda", moeda)
                .param("periodoInicio", DatasSql.gravar(periodoInicio))
                .param("periodoFim", DatasSql.gravar(periodoFim))
                .param("registrosProvedor", registrosProvedor)
                .param("iniciadaEm", DatasSql.gravar(iniciadaEm))
                .update();

        ConciliacaoPersistida conciliacao = buscarConciliacaoPorExtrato(
                idEmpresa, provedor, identificadorExtrato).orElseThrow();
        return new InicioConciliacao(conciliacao, inseridos == 1);
    }

    public Optional<ConciliacaoPersistida> buscarConciliacaoPorExtrato(
            UUID idEmpresa,
            String provedor,
            String identificadorExtrato) {
        return banco.sql("""
                        SELECT id_conciliacao, provedor, identificador_extrato,
                               hash_extrato, moeda, periodo_inicio, periodo_fim,
                               registros_provedor, registros_locais,
                               registros_duplicados, registros_analisados,
                               divergencias_encontradas, status, iniciada_em,
                               concluida_em
                          FROM conciliacao
                         WHERE id_empresa = :idEmpresa
                           AND provedor = :provedor
                           AND identificador_extrato = :identificadorExtrato
                        """)
                .param("idEmpresa", idEmpresa)
                .param("provedor", provedor)
                .param("identificadorExtrato", identificadorExtrato)
                .query(this::mapearConciliacaoPersistida)
                .optional();
    }

    public void registrarOcorrenciaConciliacao(
            UUID idConciliacao,
            UUID idPagamento,
            String tipo,
            String detalhes,
            Instant agora) {
        banco.sql("""
                        INSERT INTO ocorrencia_conciliacao (
                            id_ocorrencia, id_conciliacao, id_pagamento,
                            tipo, detalhes, identificada_em
                        ) VALUES (
                            :idOcorrencia, :idConciliacao, :idPagamento,
                            :tipo, :detalhes, :agora
                        )
                        """)
                .param("idOcorrencia", UUID.randomUUID())
                .param("idConciliacao", idConciliacao)
                .param("idPagamento", idPagamento)
                .param("tipo", tipo)
                .param("detalhes", limitar(detalhes, 2_000))
                .param("agora", DatasSql.gravar(agora))
                .update();
    }

    public List<OcorrenciaConciliacao> listarOcorrenciasConciliacao(UUID idConciliacao) {
        return banco.sql("""
                        SELECT id_pagamento, tipo, detalhes, identificada_em
                          FROM ocorrencia_conciliacao
                         WHERE id_conciliacao = :idConciliacao
                         ORDER BY identificada_em, id_ocorrencia
                        """)
                .param("idConciliacao", idConciliacao)
                .query((resultado, linha) -> new OcorrenciaConciliacao(
                        resultado.getObject("id_pagamento", UUID.class),
                        resultado.getString("tipo"),
                        resultado.getString("detalhes"),
                        DatasSql.ler(resultado, "identificada_em")))
                .list();
    }

    public void concluirConciliacao(
            UUID idConciliacao,
            int registrosLocais,
            int registrosDuplicados,
            int registrosAnalisados,
            int divergenciasEncontradas,
            Instant concluidaEm) {
        int atualizados = banco.sql("""
                        UPDATE conciliacao
                           SET registros_locais = :registrosLocais,
                               registros_duplicados = :registrosDuplicados,
                               registros_analisados = :registrosAnalisados,
                               divergencias_encontradas = :divergencias,
                               status = :status,
                               concluida_em = :concluidaEm
                         WHERE id_conciliacao = :idConciliacao
                           AND status = 'PROCESSANDO'
                        """)
                .param("registrosLocais", registrosLocais)
                .param("registrosDuplicados", registrosDuplicados)
                .param("registrosAnalisados", registrosAnalisados)
                .param("divergencias", divergenciasEncontradas)
                .param("status", divergenciasEncontradas == 0
                        ? "CONCLUIDA"
                        : "CONCLUIDA_COM_DIVERGENCIAS")
                .param("concluidaEm", DatasSql.gravar(concluidaEm))
                .param("idConciliacao", idConciliacao)
                .update();
        if (atualizados != 1) {
            throw new IllegalStateException("A conciliacao nao estava disponivel para conclusao");
        }
    }

    public List<RespostaConciliacaoResumo> listarConciliacoes(
            UUID idEmpresa,
            int limite) {
        return banco.sql("""
                        SELECT id_conciliacao, provedor, identificador_extrato,
                               periodo_inicio, periodo_fim, registros_provedor,
                               registros_locais, registros_duplicados,
                               registros_analisados, divergencias_encontradas,
                               status, concluida_em
                          FROM conciliacao
                         WHERE id_empresa = :idEmpresa
                         ORDER BY iniciada_em DESC, id_conciliacao
                         LIMIT :limite
                        """)
                .param("idEmpresa", idEmpresa)
                .param("limite", limite)
                .query((resultado, linha) -> new RespostaConciliacaoResumo(
                        resultado.getObject("id_conciliacao", UUID.class),
                        resultado.getString("provedor"),
                        resultado.getString("identificador_extrato"),
                        DatasSql.ler(resultado, "periodo_inicio"),
                        DatasSql.ler(resultado, "periodo_fim"),
                        resultado.getInt("registros_provedor"),
                        resultado.getInt("registros_locais"),
                        resultado.getInt("registros_duplicados"),
                        resultado.getInt("registros_analisados"),
                        resultado.getInt("divergencias_encontradas"),
                        resultado.getString("status"),
                        DatasSql.ler(resultado, "concluida_em")))
                .list();
    }

    public PaginaDivergencias listarDivergencias(
            UUID idEmpresa,
            String status,
            int pagina,
            int tamanho) {
        long total = banco.sql("""
                        SELECT COUNT(*)
                          FROM divergencia_conciliacao
                         WHERE id_empresa = :idEmpresa
                           AND (:status IS NULL OR status = :status)
                        """)
                .param("idEmpresa", idEmpresa)
                .param("status", status, java.sql.Types.VARCHAR)
                .query(Long.class)
                .single();
        List<RespostaDivergencia> itens = banco.sql("""
                        SELECT id_divergencia, id_pagamento, tipo, detalhes,
                               status, observacao_resolucao, identificada_em,
                               atualizado_em, resolvido_em
                          FROM divergencia_conciliacao
                         WHERE id_empresa = :idEmpresa
                           AND (:status IS NULL OR status = :status)
                         ORDER BY identificada_em DESC, id_divergencia
                         LIMIT :limite OFFSET :deslocamento
                        """)
                .param("idEmpresa", idEmpresa)
                .param("status", status, java.sql.Types.VARCHAR)
                .param("limite", tamanho)
                .param("deslocamento", (long) pagina * tamanho)
                .query(this::mapearDivergencia)
                .list();
        return new PaginaDivergencias(itens, pagina, tamanho, total);
    }

    public Optional<RespostaDivergencia> atualizarDivergencia(
            UUID idEmpresa,
            UUID idDivergencia,
            String statusNovo,
            String observacao,
            Instant agora) {
        Optional<String> statusAnterior = banco.sql("""
                        SELECT status
                          FROM divergencia_conciliacao
                         WHERE id_empresa = :idEmpresa AND id_divergencia = :idDivergencia
                         FOR UPDATE
                        """)
                .param("idEmpresa", idEmpresa)
                .param("idDivergencia", idDivergencia)
                .query(String.class)
                .optional();
        if (statusAnterior.isEmpty()) {
            return Optional.empty();
        }
        banco.sql("""
                        UPDATE divergencia_conciliacao
                           SET status = :statusNovo,
                               observacao_resolucao = :observacao,
                               atualizado_em = :agora,
                               resolvido_em = CASE
                                   WHEN :statusNovo = 'RESOLVIDA' THEN :agora
                                   ELSE NULL
                               END
                         WHERE id_empresa = :idEmpresa AND id_divergencia = :idDivergencia
                        """)
                .param("statusNovo", statusNovo)
                .param("observacao", observacao)
                .param("agora", DatasSql.gravar(agora))
                .param("idEmpresa", idEmpresa)
                .param("idDivergencia", idDivergencia)
                .update();
        banco.sql("""
                        INSERT INTO auditoria_divergencia (
                            id_auditoria, id_divergencia, id_empresa,
                            status_anterior, status_novo, observacao, alterada_em
                        ) VALUES (
                            :idAuditoria, :idDivergencia, :idEmpresa,
                            :statusAnterior, :statusNovo, :observacao, :agora
                        )
                        """)
                .param("idAuditoria", UUID.randomUUID())
                .param("idDivergencia", idDivergencia)
                .param("idEmpresa", idEmpresa)
                .param("statusAnterior", statusAnterior.get())
                .param("statusNovo", statusNovo)
                .param("observacao", observacao)
                .param("agora", DatasSql.gravar(agora))
                .update();
        return banco.sql("""
                        SELECT id_divergencia, id_pagamento, tipo, detalhes,
                               status, observacao_resolucao, identificada_em,
                               atualizado_em, resolvido_em
                          FROM divergencia_conciliacao
                         WHERE id_empresa = :idEmpresa AND id_divergencia = :idDivergencia
                        """)
                .param("idEmpresa", idEmpresa)
                .param("idDivergencia", idDivergencia)
                .query(this::mapearDivergencia)
                .optional();
    }

    private RespostaPagamento mapearResposta(ResultSet resultado, int linha) throws SQLException {
        return new RespostaPagamento(
                resultado.getObject("id_pagamento", UUID.class),
                resultado.getObject("id_compra", UUID.class),
                resultado.getBigDecimal("valor"),
                resultado.getString("moeda"),
                resultado.getString("status"),
                resultado.getString("id_autorizacao"),
                resultado.getString("motivo"),
                MetodoPagamento.valueOf(resultado.getString("metodo_pagamento")),
                resultado.getInt("parcelas"),
                resultado.getString("provedor"),
                resultado.getString("txid"),
                resultado.getString("copia_cola_pix"),
                resultado.getString("imagem_qr_code_base64"),
                DatasSql.ler(resultado, "expira_em"),
                DatasSql.ler(resultado, "atualizado_em"));
    }

    private RespostaDivergencia mapearDivergencia(ResultSet resultado, int linha) throws SQLException {
        return new RespostaDivergencia(
                resultado.getObject("id_divergencia", UUID.class),
                resultado.getObject("id_pagamento", UUID.class),
                resultado.getString("tipo"),
                resultado.getString("detalhes"),
                resultado.getString("status"),
                resultado.getString("observacao_resolucao"),
                DatasSql.ler(resultado, "identificada_em"),
                DatasSql.ler(resultado, "atualizado_em"),
                DatasSql.ler(resultado, "resolvido_em"));
    }

    private PagamentoConciliavel mapearPagamentoConciliavel(ResultSet resultado, int linha)
            throws SQLException {
        return new PagamentoConciliavel(
                resultado.getObject("id_pagamento", UUID.class),
                resultado.getBigDecimal("valor"),
                resultado.getString("moeda"),
                resultado.getString("status"),
                resultado.getString("id_autorizacao"),
                resultado.getString("provedor"),
                DatasSql.ler(resultado, "criado_em"));
    }

    private ConciliacaoPersistida mapearConciliacaoPersistida(ResultSet resultado, int linha)
            throws SQLException {
        return new ConciliacaoPersistida(
                resultado.getObject("id_conciliacao", UUID.class),
                resultado.getString("provedor"),
                resultado.getString("identificador_extrato"),
                resultado.getString("hash_extrato"),
                resultado.getString("moeda"),
                DatasSql.ler(resultado, "periodo_inicio"),
                DatasSql.ler(resultado, "periodo_fim"),
                resultado.getInt("registros_provedor"),
                resultado.getInt("registros_locais"),
                resultado.getInt("registros_duplicados"),
                resultado.getInt("registros_analisados"),
                resultado.getInt("divergencias_encontradas"),
                resultado.getString("status"),
                DatasSql.ler(resultado, "iniciada_em"),
                DatasSql.ler(resultado, "concluida_em"));
    }

    private Pagamento mapearPagamento(ResultSet resultado, int linha) throws SQLException {
        return new Pagamento(
                resultado.getObject("id_pagamento", UUID.class),
                resultado.getObject("id_empresa", UUID.class),
                resultado.getObject("id_compra", UUID.class),
                StatusPagamento.valueOf(resultado.getString("status")),
                MetodoPagamento.valueOf(resultado.getString("metodo_pagamento")),
                resultado.getString("provedor"),
                resultado.getString("txid"));
    }

    private String limitar(String texto, int limite) {
        if (texto == null || texto.length() <= limite) {
            return texto;
        }
        return texto.substring(0, limite);
    }

    public record Pagamento(
            UUID idPagamento,
            UUID idEmpresa,
            UUID idCompra,
            StatusPagamento status,
            MetodoPagamento metodoPagamento,
            String provedor,
            String txid) {

        public Pagamento(
                UUID idPagamento,
                UUID idEmpresa,
                UUID idCompra,
                StatusPagamento status) {
            this(idPagamento, idEmpresa, idCompra, status, MetodoPagamento.CARTAO, null, null);
        }
    }

    public record PagamentoConciliavel(
            UUID idPagamento,
            BigDecimal valor,
            String moeda,
            String status,
            String idAutorizacao,
            String provedor,
            Instant criadoEm) {
    }

    public record ConciliacaoPersistida(
            UUID idConciliacao,
            String provedor,
            String identificadorExtrato,
            String hashExtrato,
            String moeda,
            Instant periodoInicio,
            Instant periodoFim,
            int registrosProvedor,
            int registrosLocais,
            int registrosDuplicados,
            int registrosAnalisados,
            int divergenciasEncontradas,
            String status,
            Instant iniciadaEm,
            Instant concluidaEm) {
    }

    public record InicioConciliacao(ConciliacaoPersistida conciliacao, boolean nova) {
    }

    public record OcorrenciaConciliacao(
            UUID idPagamento,
            String tipo,
            String detalhes,
            Instant identificadaEm) {
    }
}
