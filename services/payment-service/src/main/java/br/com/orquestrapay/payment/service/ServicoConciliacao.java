package br.com.orquestrapay.payment.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import br.com.orquestrapay.payment.api.PedidoConciliacao;
import br.com.orquestrapay.payment.api.RegistroProvedor;
import br.com.orquestrapay.payment.api.ResultadoConciliacao;
import br.com.orquestrapay.payment.api.AtualizacaoDivergencia;
import br.com.orquestrapay.payment.api.PaginaDivergencias;
import br.com.orquestrapay.payment.api.RespostaConciliacaoResumo;
import br.com.orquestrapay.payment.api.RespostaDivergencia;
import br.com.orquestrapay.payment.data.RepositorioPagamentos;
import br.com.orquestrapay.platform.web.ExcecaoNegocio;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ServicoConciliacao {

    private static final int LIMITE_REGISTROS_LOCAIS = 500;

    private final RepositorioPagamentos repositorio;
    private final Clock relogio;
    private final MeterRegistry metricas;

    public ServicoConciliacao(
            RepositorioPagamentos repositorio,
            Clock relogio,
            MeterRegistry metricas) {
        this.repositorio = repositorio;
        this.relogio = relogio;
        this.metricas = metricas;
    }

    @Transactional
    public ResultadoConciliacao conciliar(UUID idEmpresa, PedidoConciliacao pedido) {
        var iniciadaEm = relogio.instant();
        String hashExtrato = calcularHashExtrato(pedido);
        var inicio = repositorio.iniciarConciliacao(
                idEmpresa,
                pedido.provedor(),
                pedido.identificadorExtrato(),
                hashExtrato,
                pedido.moeda(),
                pedido.periodoInicio(),
                pedido.periodoFim(),
                pedido.registros().size(),
                iniciadaEm);
        var conciliacao = inicio.conciliacao();

        if (!MessageDigest.isEqual(
                hashExtrato.getBytes(StandardCharsets.US_ASCII),
                conciliacao.hashExtrato().getBytes(StandardCharsets.US_ASCII))) {
            throw new ExcecaoNegocio(
                    HttpStatus.CONFLICT,
                    "extrato-conciliacao-alterado",
                    "O identificador do extrato ja foi usado com outro conteudo");
        }
        if (!inicio.nova()) {
            if ("PROCESSANDO".equals(conciliacao.status())) {
                throw new ExcecaoNegocio(
                        HttpStatus.CONFLICT,
                        "conciliacao-em-processamento",
                        "Este extrato ja esta sendo conciliado");
            }
            metricas.counter(
                    "orquestrapay.conciliacoes.execucoes",
                    "resultado",
                    "reaproveitada").increment();
            return montarResultado(conciliacao, true);
        }

        Map<UUID, List<RegistroProvedor>> registrosPorPagamento = pedido.registros().stream()
                .collect(Collectors.groupingBy(
                        RegistroProvedor::idPagamento,
                        LinkedHashMap::new,
                        Collectors.toList()));
        var pagamentosPorId = repositorio.buscarConciliaveisPorIds(
                idEmpresa,
                List.copyOf(registrosPorPagamento.keySet()));
        var pagamentosNaJanela = repositorio.buscarConciliaveisNaJanela(
                idEmpresa,
                pedido.provedor(),
                pedido.moeda(),
                pedido.periodoInicio(),
                pedido.periodoFim(),
                LIMITE_REGISTROS_LOCAIS + 1);
        if (pagamentosNaJanela.size() > LIMITE_REGISTROS_LOCAIS) {
            throw new ExcecaoNegocio(
                    HttpStatus.CONTENT_TOO_LARGE,
                    "janela-conciliacao-muito-ampla",
                    "A janela possui mais de 500 pagamentos locais; divida o extrato em periodos menores");
        }

        var divergencias = new ArrayList<String>();
        int registrosDuplicados = 0;
        for (var grupo : registrosPorPagamento.entrySet()) {
            UUID idPagamento = grupo.getKey();
            List<RegistroProvedor> registros = grupo.getValue();
            RegistroProvedor registro = registros.getFirst();
            if (registros.size() > 1) {
                int repeticoes = registros.size() - 1;
                registrosDuplicados += repeticoes;
                registrarDivergencia(
                        idEmpresa,
                        conciliacao.idConciliacao(),
                        idPagamento,
                        "REGISTRO_DUPLICADO_PROVEDOR",
                        "Pagamento %s apareceu %d vezes no extrato do provedor"
                                .formatted(idPagamento, registros.size()),
                        divergencias);
            }
            if (registro.ocorridoEm().isBefore(pedido.periodoInicio())
                    || !registro.ocorridoEm().isBefore(pedido.periodoFim())) {
                registrarDivergencia(
                        idEmpresa,
                        conciliacao.idConciliacao(),
                        idPagamento,
                        "REGISTRO_FORA_PERIODO",
                        "Pagamento %s ocorreu em %s, fora da janela do extrato"
                                .formatted(idPagamento, registro.ocorridoEm()),
                        divergencias);
            }
            if (!pedido.moeda().equals(registro.moeda())) {
                registrarDivergencia(
                        idEmpresa,
                        conciliacao.idConciliacao(),
                        idPagamento,
                        "MOEDA_FORA_EXTRATO",
                        "Pagamento %s esta em %s, mas o extrato esta em %s"
                                .formatted(idPagamento, registro.moeda(), pedido.moeda()),
                        divergencias);
            }

            var local = pagamentosPorId.get(idPagamento);
            if (local == null) {
                registrarDivergencia(
                        idEmpresa,
                        conciliacao.idConciliacao(),
                        idPagamento,
                        "AUSENTE_LOCALMENTE",
                        "Pagamento %s existe no provedor, mas nao localmente".formatted(idPagamento),
                        divergencias);
                continue;
            }
            if (local.criadoEm().isBefore(pedido.periodoInicio())
                    || !local.criadoEm().isBefore(pedido.periodoFim())) {
                registrarDivergencia(
                        idEmpresa,
                        conciliacao.idConciliacao(),
                        idPagamento,
                        "REGISTRO_LOCAL_FORA_PERIODO",
                        "Pagamento %s foi criado localmente em %s, fora da janela do extrato"
                                .formatted(idPagamento, local.criadoEm()),
                        divergencias);
            }
            comparar(
                    idEmpresa,
                    conciliacao.idConciliacao(),
                    pedido.provedor(),
                    registro,
                    local,
                    divergencias);
        }

        Set<UUID> idsNoProvedor = registrosPorPagamento.keySet();
        int registrosSomenteLocais = 0;
        for (var local : pagamentosNaJanela) {
            if (!idsNoProvedor.contains(local.idPagamento())) {
                registrosSomenteLocais++;
                registrarDivergencia(
                        idEmpresa,
                        conciliacao.idConciliacao(),
                        local.idPagamento(),
                        "AUSENTE_NO_PROVEDOR",
                        "Pagamento %s existe localmente, mas nao consta no extrato do provedor"
                                .formatted(local.idPagamento()),
                        divergencias);
            }
        }

        int registrosAnalisados = pedido.registros().size() + registrosSomenteLocais;
        var concluidaEm = relogio.instant();
        repositorio.concluirConciliacao(
                conciliacao.idConciliacao(),
                pagamentosNaJanela.size(),
                registrosDuplicados,
                registrosAnalisados,
                divergencias.size(),
                concluidaEm);
        String status = divergencias.isEmpty()
                ? "CONCLUIDA"
                : "CONCLUIDA_COM_DIVERGENCIAS";
        metricas.counter(
                "orquestrapay.conciliacoes.execucoes",
                "resultado",
                divergencias.isEmpty() ? "sem_divergencia" : "com_divergencia").increment();
        return new ResultadoConciliacao(
                conciliacao.idConciliacao(),
                pedido.provedor(),
                pedido.identificadorExtrato(),
                pedido.periodoInicio(),
                pedido.periodoFim(),
                pedido.registros().size(),
                pagamentosNaJanela.size(),
                registrosDuplicados,
                registrosAnalisados,
                divergencias.size(),
                divergencias,
                status,
                concluidaEm,
                false);
    }

    private void comparar(
            UUID idEmpresa,
            UUID idConciliacao,
            String provedorExtrato,
            RegistroProvedor registro,
            RepositorioPagamentos.PagamentoConciliavel local,
            List<String> divergencias) {
        UUID idPagamento = registro.idPagamento();
        if (!Objects.equals(local.provedor(), provedorExtrato)) {
            registrarDivergencia(
                    idEmpresa,
                    idConciliacao,
                    idPagamento,
                    "PROVEDOR_DIVERGENTE",
                    "Pagamento %s: provedor local %s, provedor do extrato %s"
                            .formatted(idPagamento, local.provedor(), provedorExtrato),
                    divergencias);
        }
        if (local.valor().compareTo(registro.valor()) != 0) {
            registrarDivergencia(
                    idEmpresa,
                    idConciliacao,
                    idPagamento,
                    "VALOR_DIVERGENTE",
                    "Pagamento %s: valor local %s, valor no provedor %s"
                            .formatted(idPagamento, local.valor(), registro.valor()),
                    divergencias);
        }
        if (!local.moeda().equals(registro.moeda())) {
            registrarDivergencia(
                    idEmpresa,
                    idConciliacao,
                    idPagamento,
                    "MOEDA_DIVERGENTE",
                    "Pagamento %s: moeda local %s, moeda no provedor %s"
                            .formatted(idPagamento, local.moeda(), registro.moeda()),
                    divergencias);
        }
        if (!local.status().equals(registro.status())) {
            registrarDivergencia(
                    idEmpresa,
                    idConciliacao,
                    idPagamento,
                    "STATUS_DIVERGENTE",
                    "Pagamento %s: status local %s, status no provedor %s"
                            .formatted(idPagamento, local.status(), registro.status()),
                    divergencias);
        }
        if (!Objects.equals(local.idAutorizacao(), registro.idTransacaoProvedor())) {
            registrarDivergencia(
                    idEmpresa,
                    idConciliacao,
                    idPagamento,
                    "IDENTIFICADOR_DIVERGENTE",
                    "Pagamento %s possui identificador de transacao diferente no provedor"
                            .formatted(idPagamento),
                    divergencias);
        }
    }

    private void registrarDivergencia(
            UUID idEmpresa,
            UUID idConciliacao,
            UUID idPagamento,
            String tipo,
            String detalhes,
            List<String> divergencias) {
        Instant agora = relogio.instant();
        divergencias.add("[%s] %s".formatted(tipo, detalhes));
        metricas.counter(
                "orquestrapay.conciliacoes.divergencias",
                "tipo",
                tipo).increment();
        repositorio.registrarOcorrenciaConciliacao(
                idConciliacao,
                idPagamento,
                tipo,
                detalhes,
                agora);
        repositorio.registrarDivergencia(
                idEmpresa,
                idPagamento,
                tipo,
                detalhes,
                agora);
    }

    private ResultadoConciliacao montarResultado(
            RepositorioPagamentos.ConciliacaoPersistida conciliacao,
            boolean reaproveitada) {
        List<String> divergencias = repositorio
                .listarOcorrenciasConciliacao(conciliacao.idConciliacao())
                .stream()
                .map(ocorrencia -> "[%s] %s".formatted(
                        ocorrencia.tipo(), ocorrencia.detalhes()))
                .toList();
        return new ResultadoConciliacao(
                conciliacao.idConciliacao(),
                conciliacao.provedor(),
                conciliacao.identificadorExtrato(),
                conciliacao.periodoInicio(),
                conciliacao.periodoFim(),
                conciliacao.registrosProvedor(),
                conciliacao.registrosLocais(),
                conciliacao.registrosDuplicados(),
                conciliacao.registrosAnalisados(),
                conciliacao.divergenciasEncontradas(),
                divergencias,
                conciliacao.status(),
                conciliacao.concluidaEm(),
                reaproveitada);
    }

    private String calcularHashExtrato(PedidoConciliacao pedido) {
        var conteudo = new StringBuilder();
        adicionarCampo(conteudo, pedido.provedor());
        adicionarCampo(conteudo, pedido.identificadorExtrato());
        adicionarCampo(conteudo, pedido.periodoInicio().toString());
        adicionarCampo(conteudo, pedido.periodoFim().toString());
        adicionarCampo(conteudo, pedido.moeda());

        pedido.registros().stream()
                .map(this::registroCanonico)
                .sorted(Comparator.naturalOrder())
                .forEach(registro -> adicionarCampo(conteudo, registro));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(conteudo.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException excecao) {
            throw new IllegalStateException("SHA-256 indisponivel", excecao);
        }
    }

    private String registroCanonico(RegistroProvedor registro) {
        var conteudo = new StringBuilder();
        adicionarCampo(conteudo, registro.idPagamento().toString());
        adicionarCampo(conteudo, normalizar(registro.valor()));
        adicionarCampo(conteudo, registro.moeda());
        adicionarCampo(conteudo, registro.status());
        adicionarCampo(conteudo, registro.idTransacaoProvedor());
        adicionarCampo(conteudo, registro.ocorridoEm().toString());
        return conteudo.toString();
    }

    private String normalizar(BigDecimal valor) {
        return valor.stripTrailingZeros().toPlainString();
    }

    private void adicionarCampo(StringBuilder destino, String valor) {
        destino.append(valor.length()).append(':').append(valor);
    }

    @Transactional(readOnly = true)
    public List<RespostaConciliacaoResumo> listar(UUID idEmpresa, int limite) {
        return repositorio.listarConciliacoes(idEmpresa, limite);
    }

    @Transactional(readOnly = true)
    public PaginaDivergencias listarDivergencias(
            UUID idEmpresa,
            String status,
            int pagina,
            int tamanho) {
        if (status != null && !Set.of("ABERTA", "INVESTIGANDO", "RESOLVIDA").contains(status)) {
            throw new ExcecaoNegocio(
                    HttpStatus.BAD_REQUEST,
                    "status-divergencia-invalido",
                    "Use ABERTA, INVESTIGANDO ou RESOLVIDA");
        }
        return repositorio.listarDivergencias(idEmpresa, status, pagina, tamanho);
    }

    @Transactional
    public RespostaDivergencia atualizarDivergencia(
            UUID idEmpresa,
            UUID idDivergencia,
            AtualizacaoDivergencia atualizacao) {
        return repositorio.atualizarDivergencia(
                        idEmpresa,
                        idDivergencia,
                        atualizacao.status(),
                        atualizacao.observacao(),
                        relogio.instant())
                .orElseThrow(() -> new ExcecaoNegocio(
                        HttpStatus.NOT_FOUND,
                        "divergencia-nao-encontrada",
                        "Divergencia nao encontrada para esta empresa"));
    }
}
