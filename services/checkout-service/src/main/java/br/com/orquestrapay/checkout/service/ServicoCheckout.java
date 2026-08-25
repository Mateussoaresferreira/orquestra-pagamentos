package br.com.orquestrapay.checkout.service;

import static br.com.orquestrapay.contracts.TiposEventos.ANALISAR_RISCO;
import static br.com.orquestrapay.contracts.TiposEventos.AUTORIZAR_PAGAMENTO;
import static br.com.orquestrapay.contracts.TiposEventos.COMPRA_COMPENSADA;
import static br.com.orquestrapay.contracts.TiposEventos.COMPRA_CONCLUIDA;
import static br.com.orquestrapay.contracts.TiposEventos.COMPRA_RECUSADA;
import static br.com.orquestrapay.contracts.TiposEventos.ESTOQUE_LIBERADO;
import static br.com.orquestrapay.contracts.TiposEventos.ESTOQUE_RECUSADO;
import static br.com.orquestrapay.contracts.TiposEventos.ESTOQUE_RESERVADO;
import static br.com.orquestrapay.contracts.TiposEventos.ESTORNAR_PAGAMENTO;
import static br.com.orquestrapay.contracts.TiposEventos.LANCAMENTOS_RECUSADOS;
import static br.com.orquestrapay.contracts.TiposEventos.LANCAMENTOS_REGISTRADOS;
import static br.com.orquestrapay.contracts.TiposEventos.LIBERAR_ESTOQUE;
import static br.com.orquestrapay.contracts.TiposEventos.PAGAMENTO_AUTORIZADO;
import static br.com.orquestrapay.contracts.TiposEventos.PAGAMENTO_ESTORNADO;
import static br.com.orquestrapay.contracts.TiposEventos.PAGAMENTO_PENDENTE;
import static br.com.orquestrapay.contracts.TiposEventos.PAGAMENTO_RECUSADO;
import static br.com.orquestrapay.contracts.TiposEventos.REGISTRAR_LANCAMENTOS;
import static br.com.orquestrapay.contracts.TiposEventos.RESERVAR_ESTOQUE;
import static br.com.orquestrapay.contracts.TiposEventos.RISCO_APROVADO;
import static br.com.orquestrapay.contracts.TiposEventos.RISCO_REPROVADO;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import br.com.orquestrapay.checkout.api.NovaCompra;
import br.com.orquestrapay.checkout.api.RegistroHistorico;
import br.com.orquestrapay.checkout.api.RespostaCompra;
import br.com.orquestrapay.checkout.data.RepositorioCompras;
import br.com.orquestrapay.checkout.domain.Compra;
import br.com.orquestrapay.checkout.domain.StatusCompra;
import br.com.orquestrapay.contracts.CompraFinalizada;
import br.com.orquestrapay.contracts.EventoSaga;
import br.com.orquestrapay.contracts.ItemCompra;
import br.com.orquestrapay.contracts.MetodoPagamento;
import br.com.orquestrapay.contracts.ResultadoEstoque;
import br.com.orquestrapay.contracts.ResultadoLancamentos;
import br.com.orquestrapay.contracts.ResultadoPagamento;
import br.com.orquestrapay.contracts.ResultadoRisco;
import br.com.orquestrapay.contracts.SolicitacaoAnaliseRisco;
import br.com.orquestrapay.contracts.SolicitacaoCompensacao;
import br.com.orquestrapay.contracts.SolicitacaoLancamentos;
import br.com.orquestrapay.contracts.SolicitacaoPagamento;
import br.com.orquestrapay.contracts.SolicitacaoReservaEstoque;
import br.com.orquestrapay.platform.event.RegistroEventos;
import br.com.orquestrapay.platform.event.RegistroMensagens;
import br.com.orquestrapay.platform.security.ProtecaoTokenPagamento;
import br.com.orquestrapay.platform.web.ExcecaoNegocio;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ServicoCheckout {

    private static final Logger log = LoggerFactory.getLogger(ServicoCheckout.class);
    private static final String ORIGEM = "servico-checkout";
    private static final String CONSUMIDOR = "checkout-orquestrador-v1";
    private static final Set<String> EVENTOS_TRATADOS = Set.of(
            ESTOQUE_RESERVADO,
            ESTOQUE_RECUSADO,
            RISCO_APROVADO,
            RISCO_REPROVADO,
            PAGAMENTO_AUTORIZADO,
            PAGAMENTO_PENDENTE,
            PAGAMENTO_RECUSADO,
            LANCAMENTOS_REGISTRADOS,
            LANCAMENTOS_RECUSADOS,
            PAGAMENTO_ESTORNADO,
            ESTOQUE_LIBERADO);

    private final RepositorioCompras repositorio;
    private final RegistroEventos eventos;
    private final RegistroMensagens mensagens;
    private final ObjectMapper json;
    private final Clock relogio;
    private final MeterRegistry metricas;
    private final ProtecaoTokenPagamento protecaoToken;

    public ServicoCheckout(
            RepositorioCompras repositorio,
            RegistroEventos eventos,
            RegistroMensagens mensagens,
            ObjectMapper json,
            Clock relogio,
            MeterRegistry metricas,
            ProtecaoTokenPagamento protecaoToken) {
        this.repositorio = repositorio;
        this.eventos = eventos;
        this.mensagens = mensagens;
        this.json = json;
        this.relogio = relogio;
        this.metricas = metricas;
        this.protecaoToken = protecaoToken;
    }

    @Transactional
    public ResultadoCriacao iniciar(UUID idEmpresa, String chaveIdempotencia, NovaCompra requisicao) {
        validarChave(chaveIdempotencia);
        validarProdutosUnicos(requisicao);
        validarPagamento(requisicao);
        String hash = calcularHash(idEmpresa, requisicao);

        repositorio.bloquearIdempotencia(idEmpresa, chaveIdempotencia);
        var existente = repositorio.buscarIdempotencia(idEmpresa, chaveIdempotencia);
        if (existente.isPresent()) {
            if (!existente.get().hashRequisicao().equals(hash)) {
                throw new ExcecaoNegocio(
                        HttpStatus.CONFLICT,
                        "idempotencia-conflitante",
                        "A mesma chave foi usada com dados diferentes");
            }
            Compra compra = buscarObrigatoria(idEmpresa, existente.get().idCompra());
            return new ResultadoCriacao(RespostaCompra.de(compra), true);
        }

        List<ItemCompra> itens = requisicao.itens().stream()
                .map(item -> new ItemCompra(item.idProduto(), item.quantidade(), item.precoUnitario()))
                .toList();
        BigDecimal total = itens.stream()
                .map(ItemCompra::subtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.UNNECESSARY);
        if (total.movePointRight(2).compareTo(BigDecimal.valueOf(requisicao.parcelas())) < 0) {
            throw new ExcecaoNegocio(
                    HttpStatus.BAD_REQUEST,
                    "parcelamento-invalido",
                    "O valor total deve permitir ao menos um centavo por parcela");
        }
        Instant agora = relogio.instant();
        UUID idCompra = UUID.randomUUID();
        UUID idReserva = UUID.randomUUID();

        Compra compra = new Compra(
                idCompra,
                idEmpresa,
                requisicao.idCliente(),
                requisicao.emailCliente(),
                requisicao.moeda(),
                requisicao.pais(),
                requisicao.identificadorDispositivo(),
                requisicao.metodoPagamento(),
                requisicao.parcelas(),
                total,
                StatusCompra.RECEBIDA,
                idReserva,
                null,
                null,
                false,
                false,
                null,
                agora,
                agora,
                itens);

        repositorio.adicionar(compra, requisicao.tokenPagamento(), chaveIdempotencia, hash);
        UUID idEvento = eventos.registrar(
                RESERVAR_ESTOQUE,
                idCompra,
                idEmpresa,
                idCompra,
                ORIGEM,
                new SolicitacaoReservaEstoque(idReserva, itens));
        repositorio.adicionarHistorico(
                idCompra,
                "Compra recebida e reserva solicitada",
                null,
                StatusCompra.RECEBIDA,
                idEvento,
                "Valor total: " + total + " " + requisicao.moeda(),
                agora);
        metricas.counter("orquestrapay.compras.iniciadas").increment();
        return new ResultadoCriacao(RespostaCompra.de(compra), false);
    }

    @Transactional(readOnly = true)
    public RespostaCompra buscar(UUID idEmpresa, UUID idCompra) {
        return RespostaCompra.de(buscarObrigatoria(idEmpresa, idCompra));
    }

    @Transactional(readOnly = true)
    public List<RegistroHistorico> buscarHistorico(UUID idEmpresa, UUID idCompra) {
        buscarObrigatoria(idEmpresa, idCompra);
        return repositorio.buscarHistorico(idEmpresa, idCompra);
    }

    @Transactional
    public void tratar(EventoSaga evento) {
        if (!EVENTOS_TRATADOS.contains(evento.getTipo())) {
            log.trace("Evento {} nao pertence ao checkout e foi ignorado", evento.getTipo());
            return;
        }

        UUID idEvento = UUID.fromString(evento.getIdEvento());
        if (!mensagens.iniciar(idEvento, CONSUMIDOR)) {
            return;
        }

        UUID idEmpresa = UUID.fromString(evento.getIdEmpresa());
        UUID idCompra = UUID.fromString(evento.getIdCompra());
        Compra compra = repositorio.buscarParaAtualizacao(idEmpresa, idCompra)
                .orElseThrow(() -> new IllegalStateException(
                        "Compra da saga nao encontrada para a empresa informada: " + idCompra));

        switch (evento.getTipo()) {
            case ESTOQUE_RESERVADO -> estoqueReservado(compra, evento, idEvento);
            case ESTOQUE_RECUSADO -> estoqueRecusado(compra, evento, idEvento);
            case RISCO_APROVADO -> riscoAprovado(compra, evento, idEvento);
            case RISCO_REPROVADO -> riscoReprovado(compra, evento, idEvento);
            case PAGAMENTO_PENDENTE -> pagamentoPendente(compra, evento, idEvento);
            case PAGAMENTO_AUTORIZADO -> pagamentoAutorizado(compra, evento, idEvento);
            case PAGAMENTO_RECUSADO -> pagamentoRecusado(compra, evento, idEvento);
            case LANCAMENTOS_REGISTRADOS -> lancamentosRegistrados(compra, evento, idEvento);
            case LANCAMENTOS_RECUSADOS -> lancamentosRecusados(compra, evento, idEvento);
            case PAGAMENTO_ESTORNADO -> pagamentoEstornado(compra, idEvento);
            case ESTOQUE_LIBERADO -> estoqueLiberado(compra, idEvento);
            default -> log.trace("Evento {} nao pertence ao checkout e foi ignorado", evento.getTipo());
        }
    }

    private void estoqueReservado(Compra compra, EventoSaga evento, UUID idEvento) {
        ResultadoEstoque resultado = ler(evento, ResultadoEstoque.class);
        transicionar(compra, StatusCompra.RECEBIDA, StatusCompra.ESTOQUE_RESERVADO,
                idEvento, "Estoque reservado", resultado.motivo());
        eventos.registrar(
                ANALISAR_RISCO,
                compra.idCompra(),
                compra.idEmpresa(),
                compra.idCompra(),
                ORIGEM,
                new SolicitacaoAnaliseRisco(
                        compra.idCliente(),
                        compra.valorTotal(),
                        compra.pais(),
                        compra.identificadorDispositivo()));
    }

    private void estoqueRecusado(Compra compra, EventoSaga evento, UUID idEvento) {
        ResultadoEstoque resultado = ler(evento, ResultadoEstoque.class);
        transicionar(compra, StatusCompra.RECEBIDA, StatusCompra.RECUSADA,
                idEvento, "Estoque recusado", resultado.motivo());
        finalizar(compra, COMPRA_RECUSADA, StatusCompra.RECUSADA, resultado.motivo());
    }

    private void riscoAprovado(Compra compra, EventoSaga evento, UUID idEvento) {
        ResultadoRisco resultado = ler(evento, ResultadoRisco.class);
        transicionar(compra, StatusCompra.ESTOQUE_RESERVADO, StatusCompra.RISCO_APROVADO,
                idEvento, "Risco aprovado", "Pontuacao: " + resultado.pontuacao());
        String tokenProtegido = compra.metodoPagamento() == MetodoPagamento.CARTAO
                ? repositorio.buscarTokenProtegido(compra.idEmpresa(), compra.idCompra())
                        .orElseThrow(() -> new IllegalStateException(
                                "Token de pagamento nao encontrado para a compra " + compra.idCompra()))
                : null;
        eventos.registrar(
                AUTORIZAR_PAGAMENTO,
                compra.idCompra(),
                compra.idEmpresa(),
                compra.idCompra(),
                ORIGEM,
                new SolicitacaoPagamento(
                        compra.valorTotal(),
                        compra.moeda(),
                        tokenProtegido,
                        compra.metodoPagamento(),
                        compra.parcelas()));
    }

    private void riscoReprovado(Compra compra, EventoSaga evento, UUID idEvento) {
        ResultadoRisco resultado = ler(evento, ResultadoRisco.class);
        transicionar(compra, StatusCompra.ESTOQUE_RESERVADO, StatusCompra.RECUSADA,
                idEvento, "Risco reprovado", resultado.motivo());
        solicitarLiberacao(compra, resultado.motivo());
        finalizar(compra, COMPRA_RECUSADA, StatusCompra.RECUSADA, resultado.motivo());
    }

    private void pagamentoAutorizado(Compra compra, EventoSaga evento, UUID idEvento) {
        ResultadoPagamento resultado = ler(evento, ResultadoPagamento.class);
        repositorio.vincularPagamento(compra.idCompra(), resultado.idPagamento(), relogio.instant());
        StatusCompra esperado = compra.status() == StatusCompra.AGUARDANDO_PAGAMENTO
                ? StatusCompra.AGUARDANDO_PAGAMENTO
                : StatusCompra.RISCO_APROVADO;
        transicionar(compra, esperado, StatusCompra.PAGAMENTO_AUTORIZADO,
                idEvento, "Pagamento autorizado", "Autorizacao: " + resultado.idAutorizacao());
        eventos.registrar(
                REGISTRAR_LANCAMENTOS,
                compra.idCompra(),
                compra.idEmpresa(),
                compra.idCompra(),
                ORIGEM,
                new SolicitacaoLancamentos(
                        resultado.idPagamento(),
                        compra.valorTotal(),
                        compra.moeda(),
                        compra.parcelas()));
    }

    private void pagamentoRecusado(Compra compra, EventoSaga evento, UUID idEvento) {
        ResultadoPagamento resultado = ler(evento, ResultadoPagamento.class);
        StatusCompra esperado = compra.status() == StatusCompra.AGUARDANDO_PAGAMENTO
                ? StatusCompra.AGUARDANDO_PAGAMENTO
                : StatusCompra.RISCO_APROVADO;
        transicionar(compra, esperado, StatusCompra.RECUSADA,
                idEvento, "Pagamento recusado", resultado.motivo());
        solicitarLiberacao(compra, resultado.motivo());
        finalizar(compra, COMPRA_RECUSADA, StatusCompra.RECUSADA, resultado.motivo());
    }

    private void pagamentoPendente(Compra compra, EventoSaga evento, UUID idEvento) {
        ResultadoPagamento resultado = ler(evento, ResultadoPagamento.class);
        repositorio.vincularPagamento(compra.idCompra(), resultado.idPagamento(), relogio.instant());
        transicionar(
                compra,
                StatusCompra.RISCO_APROVADO,
                StatusCompra.AGUARDANDO_PAGAMENTO,
                idEvento,
                "Cobranca PIX criada",
                "Aguardando confirmacao do pagamento");
    }

    private void lancamentosRegistrados(Compra compra, EventoSaga evento, UUID idEvento) {
        ResultadoLancamentos resultado = ler(evento, ResultadoLancamentos.class);
        repositorio.vincularTransacaoContabil(
                compra.idCompra(), resultado.idTransacaoContabil(), relogio.instant());
        transicionar(compra, StatusCompra.PAGAMENTO_AUTORIZADO, StatusCompra.CONCLUIDA,
                idEvento, "Lancamentos registrados", "Razao contabil balanceado");
        finalizar(compra, COMPRA_CONCLUIDA, StatusCompra.CONCLUIDA, "Compra concluida com sucesso");
        metricas.counter("orquestrapay.compras.concluidas").increment();
    }

    private void lancamentosRecusados(Compra compra, EventoSaga evento, UUID idEvento) {
        ResultadoLancamentos resultado = ler(evento, ResultadoLancamentos.class);
        transicionar(compra, StatusCompra.PAGAMENTO_AUTORIZADO, StatusCompra.COMPENSANDO,
                idEvento, "Falha contabil; compensacao iniciada", resultado.motivo());
        eventos.registrar(
                ESTORNAR_PAGAMENTO,
                compra.idCompra(),
                compra.idEmpresa(),
                compra.idCompra(),
                ORIGEM,
                new SolicitacaoCompensacao(compra.idPagamento(), resultado.motivo()));
        solicitarLiberacao(compra, resultado.motivo());
        metricas.counter("orquestrapay.compensacoes.iniciadas").increment();
    }

    private void pagamentoEstornado(Compra compra, UUID idEvento) {
        repositorio.marcarPagamentoEstornado(compra.idCompra(), relogio.instant());
        repositorio.adicionarHistorico(
                compra.idCompra(), "Pagamento estornado", compra.status(), compra.status(),
                idEvento, "Compensacao financeira concluida", relogio.instant());
        concluirCompensacao(compra.idEmpresa(), compra.idCompra());
    }

    private void estoqueLiberado(Compra compra, UUID idEvento) {
        repositorio.marcarEstoqueLiberado(compra.idCompra(), relogio.instant());
        repositorio.adicionarHistorico(
                compra.idCompra(), "Estoque liberado", compra.status(), compra.status(),
                idEvento, "Compensacao de estoque concluida", relogio.instant());
        concluirCompensacao(compra.idEmpresa(), compra.idCompra());
    }

    private void concluirCompensacao(UUID idEmpresa, UUID idCompra) {
        Compra atual = repositorio.buscarParaAtualizacao(idEmpresa, idCompra).orElseThrow();
        if (atual.status() == StatusCompra.COMPENSANDO
                && atual.pagamentoEstornado()
                && atual.estoqueLiberado()) {
            transicionar(atual, StatusCompra.COMPENSANDO, StatusCompra.COMPENSADA,
                    null, "Compensacao concluida", "Pagamento estornado e estoque liberado");
            finalizar(atual, COMPRA_COMPENSADA, StatusCompra.COMPENSADA,
                    "Falha contabil compensada sem perda financeira");
            metricas.counter("orquestrapay.compensacoes.concluidas").increment();
        }
    }

    private void solicitarLiberacao(Compra compra, String motivo) {
        eventos.registrar(
                LIBERAR_ESTOQUE,
                compra.idCompra(),
                compra.idEmpresa(),
                compra.idCompra(),
                ORIGEM,
                new SolicitacaoCompensacao(compra.idReserva(), motivo));
    }

    private void finalizar(Compra compra, String tipo, StatusCompra status, String motivo) {
        eventos.registrar(
                tipo,
                compra.idCompra(),
                compra.idEmpresa(),
                compra.idCompra(),
                ORIGEM,
                new CompraFinalizada(status.name(), motivo, compra.emailCliente()));
    }

    private void transicionar(
            Compra compra,
            StatusCompra esperado,
            StatusCompra novo,
            UUID idEvento,
            String etapa,
            String detalhes) {
        Instant agora = relogio.instant();
        if (!repositorio.mudarStatus(compra.idCompra(), esperado, novo, detalhes, agora)) {
            throw new IllegalStateException(
                    "Transicao invalida da compra %s: esperado %s".formatted(compra.idCompra(), esperado));
        }
        repositorio.adicionarHistorico(
                compra.idCompra(), etapa, esperado, novo, idEvento, detalhes, agora);
    }

    private <T> T ler(EventoSaga evento, Class<T> tipo) {
        try {
            return json.readValue(evento.getConteudo(), tipo);
        } catch (JacksonException excecao) {
            throw new IllegalArgumentException("Payload invalido para " + evento.getTipo(), excecao);
        }
    }

    private Compra buscarObrigatoria(UUID idEmpresa, UUID idCompra) {
        return repositorio.buscar(idEmpresa, idCompra)
                .orElseThrow(() -> new ExcecaoNegocio(
                        HttpStatus.NOT_FOUND,
                        "compra-nao-encontrada",
                        "Compra nao encontrada para esta empresa"));
    }

    private void validarChave(String chave) {
        if (chave == null || chave.isBlank() || chave.length() > 120) {
            throw new ExcecaoNegocio(
                    HttpStatus.BAD_REQUEST,
                    "chave-idempotencia-invalida",
                    "O cabecalho Idempotency-Key deve ter entre 1 e 120 caracteres");
        }
    }

    private void validarProdutosUnicos(NovaCompra requisicao) {
        Set<UUID> produtos = new HashSet<>();
        boolean repetido = requisicao.itens().stream()
                .map(item -> item.idProduto())
                .anyMatch(produto -> !produtos.add(produto));
        if (repetido) {
            throw new ExcecaoNegocio(
                    HttpStatus.BAD_REQUEST,
                    "produto-duplicado",
                    "Agrupe a quantidade do mesmo produto em um unico item");
        }
    }

    private void validarPagamento(NovaCompra requisicao) {
        if (requisicao.metodoPagamento() == MetodoPagamento.CARTAO
                && (requisicao.tokenPagamento() == null || requisicao.tokenPagamento().isBlank())) {
            throw new ExcecaoNegocio(
                    HttpStatus.BAD_REQUEST,
                    "token-pagamento-obrigatorio",
                    "Informe o token de pagamento para compras com cartao");
        }
        if (requisicao.metodoPagamento() == MetodoPagamento.PIX && requisicao.parcelas() != 1) {
            throw new ExcecaoNegocio(
                    HttpStatus.BAD_REQUEST,
                    "pix-nao-parcelado",
                    "Compras PIX devem ter exatamente uma parcela");
        }
    }

    private String calcularHash(UUID idEmpresa, NovaCompra requisicao) {
        try {
            return protecaoToken.calcularImpressao(
                    "idempotencia:" + idEmpresa,
                    json.writeValueAsBytes(requisicao));
        } catch (JacksonException excecao) {
            throw new IllegalStateException("Nao foi possivel calcular a idempotencia", excecao);
        }
    }

    public record ResultadoCriacao(RespostaCompra compra, boolean repetida) {
    }
}
