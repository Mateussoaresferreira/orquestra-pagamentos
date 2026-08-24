package br.com.orquestrapay.inventory.service;

import static br.com.orquestrapay.contracts.TiposEventos.ESTOQUE_LIBERADO;
import static br.com.orquestrapay.contracts.TiposEventos.ESTOQUE_RECUSADO;
import static br.com.orquestrapay.contracts.TiposEventos.ESTOQUE_RESERVADO;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import br.com.orquestrapay.contracts.EventoSaga;
import br.com.orquestrapay.contracts.ResultadoEstoque;
import br.com.orquestrapay.contracts.SolicitacaoCompensacao;
import br.com.orquestrapay.contracts.SolicitacaoReservaEstoque;
import br.com.orquestrapay.inventory.api.AjusteEstoque;
import br.com.orquestrapay.inventory.api.RespostaEstoque;
import br.com.orquestrapay.inventory.data.RepositorioEstoque;
import br.com.orquestrapay.inventory.domain.StatusReserva;
import br.com.orquestrapay.platform.event.RegistroEventos;
import br.com.orquestrapay.platform.event.RegistroMensagens;
import br.com.orquestrapay.platform.web.ExcecaoNegocio;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ServicoEstoque {

    private static final String ORIGEM = "servico-estoque";
    private static final String CONSUMIDOR = "estoque-v1";

    private final RepositorioEstoque repositorio;
    private final RegistroEventos eventos;
    private final RegistroMensagens mensagens;
    private final ObjectMapper json;
    private final Clock relogio;

    public ServicoEstoque(
            RepositorioEstoque repositorio,
            RegistroEventos eventos,
            RegistroMensagens mensagens,
            ObjectMapper json,
            Clock relogio) {
        this.repositorio = repositorio;
        this.eventos = eventos;
        this.mensagens = mensagens;
        this.json = json;
        this.relogio = relogio;
    }

    @Transactional
    public RespostaEstoque definir(UUID idEmpresa, UUID idProduto, AjusteEstoque ajuste) {
        repositorio.definirSaldo(idEmpresa, idProduto, ajuste.quantidadeDisponivel(), relogio.instant());
        return buscar(idEmpresa, idProduto);
    }

    @Transactional(readOnly = true)
    public RespostaEstoque buscar(UUID idEmpresa, UUID idProduto) {
        return repositorio.buscarSaldo(idEmpresa, idProduto)
                .orElseThrow(() -> new ExcecaoNegocio(
                        HttpStatus.NOT_FOUND,
                        "estoque-nao-encontrado",
                        "Saldo de estoque nao encontrado"));
    }

    @Transactional
    public void reservar(EventoSaga evento) {
        UUID idEvento = UUID.fromString(evento.getIdEvento());
        if (!mensagens.iniciar(idEvento, CONSUMIDOR)) {
            return;
        }

        UUID idEmpresa = UUID.fromString(evento.getIdEmpresa());
        UUID idCompra = UUID.fromString(evento.getIdCompra());
        SolicitacaoReservaEstoque solicitacao = ler(evento, SolicitacaoReservaEstoque.class);
        if (repositorio.reservaExiste(idEmpresa, solicitacao.idReserva())) {
            return;
        }

        var produtos = solicitacao.itens().stream()
                .map(item -> item.idProduto())
                .sorted()
                .toList();
        Map<UUID, RepositorioEstoque.SaldoBloqueado> saldos = repositorio
                .bloquearSaldos(idEmpresa, produtos)
                .stream()
                .collect(Collectors.toMap(RepositorioEstoque.SaldoBloqueado::idProduto, Function.identity()));

        String motivo = solicitacao.itens().stream()
                .filter(item -> !saldos.containsKey(item.idProduto())
                        || saldos.get(item.idProduto()).disponivel() < item.quantidade())
                .map(item -> "Saldo insuficiente para o produto " + item.idProduto())
                .findFirst()
                .orElse(null);

        if (motivo != null) {
            repositorio.salvarReserva(
                    solicitacao.idReserva(), idEmpresa, idCompra,
                    StatusReserva.RECUSADA, motivo, solicitacao.itens(), relogio.instant());
            eventos.registrar(
                    ESTOQUE_RECUSADO, idCompra, idEmpresa, idCompra, ORIGEM,
                    new ResultadoEstoque(solicitacao.idReserva(), false, motivo));
            return;
        }

        solicitacao.itens().forEach(item -> repositorio.reservar(idEmpresa, item, relogio.instant()));
        repositorio.salvarReserva(
                solicitacao.idReserva(), idEmpresa, idCompra,
                StatusReserva.RESERVADA, "Todos os itens foram reservados",
                solicitacao.itens(), relogio.instant());
        eventos.registrar(
                ESTOQUE_RESERVADO, idCompra, idEmpresa, idCompra, ORIGEM,
                new ResultadoEstoque(solicitacao.idReserva(), true, "Estoque reservado"));
    }

    @Transactional
    public void liberar(EventoSaga evento) {
        UUID idEvento = UUID.fromString(evento.getIdEvento());
        if (!mensagens.iniciar(idEvento, CONSUMIDOR)) {
            return;
        }

        UUID idEmpresaEvento = UUID.fromString(evento.getIdEmpresa());
        UUID idCompraEvento = UUID.fromString(evento.getIdCompra());
        SolicitacaoCompensacao solicitacao = ler(evento, SolicitacaoCompensacao.class);
        var reserva = repositorio.bloquearReserva(solicitacao.idReferencia())
                .orElseThrow(() -> new IllegalStateException(
                        "Reserva nao encontrada: " + solicitacao.idReferencia()));
        if (!reserva.idEmpresa().equals(idEmpresaEvento)
                || !reserva.idCompra().equals(idCompraEvento)) {
            throw new IllegalStateException(
                    "O evento de liberacao nao pertence a reserva informada");
        }
        if (reserva.status() == StatusReserva.RESERVADA) {
            repositorio.buscarItensReserva(reserva.idReserva()).forEach(item ->
                    repositorio.liberar(reserva.idEmpresa(), item, relogio.instant()));
            repositorio.marcarLiberada(reserva.idReserva(), relogio.instant());
        }

        eventos.registrar(
                ESTOQUE_LIBERADO,
                reserva.idCompra(),
                reserva.idEmpresa(),
                reserva.idCompra(),
                ORIGEM,
                new ResultadoEstoque(reserva.idReserva(), true, "Estoque liberado de forma idempotente"));
    }

    private <T> T ler(EventoSaga evento, Class<T> tipo) {
        try {
            return json.readValue(evento.getConteudo(), tipo);
        } catch (JacksonException excecao) {
            throw new IllegalArgumentException("Payload de estoque invalido", excecao);
        }
    }
}
