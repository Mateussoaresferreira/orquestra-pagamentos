package br.com.orquestrapay.checkout.service;

import static br.com.orquestrapay.contracts.TiposEventos.ANALISAR_RISCO;
import static br.com.orquestrapay.contracts.TiposEventos.AUTORIZAR_PAGAMENTO;
import static br.com.orquestrapay.contracts.TiposEventos.ESTORNAR_PAGAMENTO;
import static br.com.orquestrapay.contracts.TiposEventos.LIBERAR_ESTOQUE;
import static br.com.orquestrapay.contracts.TiposEventos.REGISTRAR_LANCAMENTOS;
import static br.com.orquestrapay.contracts.TiposEventos.RESERVAR_ESTOQUE;

import java.time.Clock;

import br.com.orquestrapay.checkout.config.PropriedadesWatchdogSaga;
import br.com.orquestrapay.checkout.data.RepositorioCompras;
import br.com.orquestrapay.checkout.domain.Compra;
import br.com.orquestrapay.contracts.SolicitacaoAnaliseRisco;
import br.com.orquestrapay.contracts.SolicitacaoCompensacao;
import br.com.orquestrapay.contracts.SolicitacaoLancamentos;
import br.com.orquestrapay.contracts.SolicitacaoPagamento;
import br.com.orquestrapay.contracts.SolicitacaoReservaEstoque;
import br.com.orquestrapay.contracts.MetodoPagamento;
import br.com.orquestrapay.platform.event.RegistroEventos;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ServicoWatchdogSaga {

    private static final String ORIGEM = "watchdog-saga";

    private final RepositorioCompras repositorio;
    private final RegistroEventos eventos;
    private final PropriedadesWatchdogSaga propriedades;
    private final MeterRegistry metricas;
    private final Clock relogio;

    public ServicoWatchdogSaga(
            RepositorioCompras repositorio,
            RegistroEventos eventos,
            PropriedadesWatchdogSaga propriedades,
            MeterRegistry metricas,
            Clock relogio) {
        this.repositorio = repositorio;
        this.eventos = eventos;
        this.propriedades = propriedades;
        this.metricas = metricas;
        this.relogio = relogio;
    }

    @Scheduled(fixedDelayString = "${orquestrapay.watchdog-saga.intervalo:300000}")
    @Transactional
    public void reativarSagasTravadas() {
        if (!propriedades.habilitado()) {
            return;
        }
        var agora = relogio.instant();
        for (Compra compra : repositorio.buscarTravadas(
                agora.minus(propriedades.limiteRecebida()),
                agora.minus(propriedades.limiteEstoqueReservado()),
                agora.minus(propriedades.limiteRiscoAprovado()),
                agora.minus(propriedades.limitePagamentoAutorizado()),
                agora.minus(propriedades.limiteCompensando()),
                propriedades.tamanhoLote())) {
            reativar(compra);
            repositorio.registrarReativacaoWatchdog(compra, agora);
            metricas.counter(
                    "orquestrapay.sagas.reativadas",
                    "status",
                    compra.status().name()).increment();
        }
    }

    private void reativar(Compra compra) {
        switch (compra.status()) {
            case RECEBIDA -> publicar(compra, RESERVAR_ESTOQUE,
                    new SolicitacaoReservaEstoque(compra.idReserva(), compra.itens()));
            case ESTOQUE_RESERVADO -> publicar(compra, ANALISAR_RISCO,
                    new SolicitacaoAnaliseRisco(
                            compra.idCliente(),
                            compra.valorTotal(),
                            compra.pais(),
                            compra.identificadorDispositivo()));
            case RISCO_APROVADO -> publicar(compra, AUTORIZAR_PAGAMENTO,
                    new SolicitacaoPagamento(
                            compra.valorTotal(),
                            compra.moeda(),
                            compra.metodoPagamento() == MetodoPagamento.CARTAO
                                    ? repositorio.buscarTokenProtegido(
                                            compra.idEmpresa(), compra.idCompra()).orElseThrow()
                                    : null,
                            compra.metodoPagamento(),
                            compra.parcelas()));
            case PAGAMENTO_AUTORIZADO -> publicar(compra, REGISTRAR_LANCAMENTOS,
                    new SolicitacaoLancamentos(
                        compra.idPagamento(),
                        compra.valorTotal(),
                        compra.moeda(),
                        compra.parcelas()));
            case COMPENSANDO -> reativarCompensacao(compra);
            default -> throw new IllegalStateException(
                    "O watchdog recebeu um estado terminal: " + compra.status());
        }
    }

    private void reativarCompensacao(Compra compra) {
        if (!compra.pagamentoEstornado() && compra.idPagamento() != null) {
            publicar(compra, ESTORNAR_PAGAMENTO,
                    new SolicitacaoCompensacao(
                            compra.idPagamento(),
                            "Watchdog reativou o estorno pendente"));
        }
        if (!compra.estoqueLiberado()) {
            publicar(compra, LIBERAR_ESTOQUE,
                    new SolicitacaoCompensacao(
                            compra.idReserva(),
                            "Watchdog reativou a liberacao de estoque"));
        }
    }

    private void publicar(Compra compra, String tipo, Object conteudo) {
        eventos.registrar(
                tipo,
                compra.idCompra(),
                compra.idEmpresa(),
                compra.idCompra(),
                ORIGEM,
                conteudo);
    }
}
