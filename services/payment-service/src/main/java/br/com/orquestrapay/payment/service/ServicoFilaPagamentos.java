package br.com.orquestrapay.payment.service;

import static br.com.orquestrapay.contracts.TiposEventos.PAGAMENTO_AUTORIZADO;
import static br.com.orquestrapay.contracts.TiposEventos.PAGAMENTO_ESTORNADO;
import static br.com.orquestrapay.contracts.TiposEventos.PAGAMENTO_PENDENTE;
import static br.com.orquestrapay.contracts.TiposEventos.PAGAMENTO_RECUSADO;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import br.com.orquestrapay.contracts.ResultadoPagamento;
import br.com.orquestrapay.payment.api.RespostaAutorizacaoProvedor;
import br.com.orquestrapay.payment.api.RespostaCobrancaPixProvedor;
import br.com.orquestrapay.payment.api.RespostaEstornoProvedor;
import br.com.orquestrapay.payment.config.PropriedadesPagamentos;
import br.com.orquestrapay.payment.data.RepositorioOperacoesPagamento;
import br.com.orquestrapay.payment.data.RepositorioPagamentos;
import br.com.orquestrapay.payment.domain.OperacaoPagamento;
import br.com.orquestrapay.payment.domain.StatusPagamento;
import br.com.orquestrapay.payment.domain.TipoOperacaoPagamento;
import br.com.orquestrapay.payment.integration.ResultadoRoteamento;
import br.com.orquestrapay.platform.event.RegistroEventos;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ServicoFilaPagamentos {

    private static final String ORIGEM = "servico-pagamento";

    private final RepositorioOperacoesPagamento operacoes;
    private final RepositorioPagamentos pagamentos;
    private final RegistroEventos eventos;
    private final PropriedadesPagamentos propriedades;
    private final Clock relogio;
    private final MeterRegistry metricas;

    public ServicoFilaPagamentos(
            RepositorioOperacoesPagamento operacoes,
            RepositorioPagamentos pagamentos,
            RegistroEventos eventos,
            PropriedadesPagamentos propriedades,
            Clock relogio,
            MeterRegistry metricas) {
        this.operacoes = operacoes;
        this.pagamentos = pagamentos;
        this.eventos = eventos;
        this.propriedades = propriedades;
        this.relogio = relogio;
        this.metricas = metricas;
    }

    @Transactional
    public List<OperacaoPagamento> reivindicar() {
        Instant agora = relogio.instant();
        UUID tokenBloqueio = UUID.randomUUID();
        List<OperacaoPagamento> lote = operacoes.reivindicar(
                propriedades.trabalhador().tamanhoLote(),
                agora,
                agora.plus(propriedades.trabalhador().duracaoBloqueio()),
                tokenBloqueio);
        lote.forEach(operacao -> pagamentos.marcarProcessando(
                operacao.idPagamento(),
                operacao.tipo() == TipoOperacaoPagamento.ESTORNAR
                        ? StatusPagamento.ESTORNANDO
                        : StatusPagamento.PROCESSANDO,
                agora));
        return lote;
    }

    @Transactional
    public void concluirCartao(
            OperacaoPagamento operacao,
            ResultadoRoteamento<RespostaAutorizacaoProvedor> roteamento,
            String impressaoToken) {
        Instant agora = relogio.instant();
        if (!operacoes.concluir(operacao, agora)) {
            return;
        }

        var resposta = roteamento.resposta();
        StatusPagamento status = resposta.aprovada()
                ? StatusPagamento.AUTORIZADO
                : StatusPagamento.RECUSADO;
        pagamentos.concluirAutorizacao(
                operacao.idPagamento(),
                status,
                roteamento.provedor(),
                impressaoToken,
                resposta.idAutorizacao(),
                resposta.motivo(),
                agora);
        eventos.registrar(
                resposta.aprovada() ? PAGAMENTO_AUTORIZADO : PAGAMENTO_RECUSADO,
                operacao.idCompra(),
                operacao.idEmpresa(),
                operacao.idCompra(),
                ORIGEM,
                new ResultadoPagamento(
                        operacao.idPagamento(),
                        resposta.idAutorizacao(),
                        resposta.aprovada(),
                        resposta.motivo(),
                        status.name(),
                        operacao.metodoPagamento(),
                        roteamento.provedor(),
                        null,
                        null,
                        null));
        registrarMetrica(operacao, status.name());
    }

    @Transactional
    public void concluirPix(
            OperacaoPagamento operacao,
            ResultadoRoteamento<RespostaCobrancaPixProvedor> roteamento) {
        Instant agora = relogio.instant();
        if (!operacoes.concluir(operacao, agora)) {
            return;
        }

        var resposta = roteamento.resposta();
        if (resposta.txid() == null || resposta.txid().isBlank()
                || resposta.copiaCola() == null || resposta.copiaCola().isBlank()
                || resposta.expiraEm() == null) {
            throw new IllegalStateException("O provedor retornou uma cobranca PIX incompleta");
        }
        pagamentos.concluirCriacaoPix(
                operacao.idPagamento(),
                roteamento.provedor(),
                resposta.txid(),
                resposta.copiaCola(),
                resposta.imagemQrCodeBase64(),
                resposta.expiraEm(),
                agora);
        eventos.registrar(
                PAGAMENTO_PENDENTE,
                operacao.idCompra(),
                operacao.idEmpresa(),
                operacao.idCompra(),
                ORIGEM,
                new ResultadoPagamento(
                        operacao.idPagamento(),
                        resposta.txid(),
                        false,
                        "Aguardando pagamento PIX",
                        StatusPagamento.AGUARDANDO_CONFIRMACAO.name(),
                        operacao.metodoPagamento(),
                        roteamento.provedor(),
                        resposta.txid(),
                        resposta.copiaCola(),
                        resposta.expiraEm()));
        registrarMetrica(operacao, StatusPagamento.AGUARDANDO_CONFIRMACAO.name());
    }

    @Transactional
    public void concluirEstorno(
            OperacaoPagamento operacao,
            ResultadoRoteamento<RespostaEstornoProvedor> roteamento) {
        Instant agora = relogio.instant();
        if (!operacoes.concluir(operacao, agora)) {
            return;
        }
        if (!roteamento.resposta().estornado()) {
            throw new IllegalStateException("O provedor nao confirmou o estorno");
        }

        String protocolo = roteamento.resposta().protocolo();
        pagamentos.marcarEstornado(operacao.idPagamento(), protocolo, agora);
        eventos.registrar(
                PAGAMENTO_ESTORNADO,
                operacao.idCompra(),
                operacao.idEmpresa(),
                operacao.idCompra(),
                ORIGEM,
                new ResultadoPagamento(
                        operacao.idPagamento(),
                        protocolo,
                        true,
                        "Pagamento estornado de forma idempotente",
                        StatusPagamento.ESTORNADO.name(),
                        operacao.metodoPagamento(),
                        roteamento.provedor(),
                        null,
                        null,
                        null));
        registrarMetrica(operacao, StatusPagamento.ESTORNADO.name());
    }

    @Transactional
    public void registrarFalha(OperacaoPagamento operacao, Throwable falha) {
        Instant agora = relogio.instant();
        String descricao = descrever(falha);
        boolean estorno = operacao.tipo() == TipoOperacaoPagamento.ESTORNAR;
        if (operacao.tentativas() < propriedades.trabalhador().maximoTentativas()) {
            Duration atraso = calcularAtraso(operacao.tentativas());
            if (operacoes.reagendar(operacao, agora.plus(atraso), descricao, agora)) {
                pagamentos.marcarAguardandoNovaTentativa(
                        operacao.idPagamento(), estorno, descricao, agora);
            }
            registrarMetrica(operacao, "REAGENDADA");
            return;
        }

        if (!operacoes.marcarFalhaDefinitiva(operacao, descricao, agora)) {
            return;
        }
        if (estorno) {
            pagamentos.marcarAguardandoNovaTentativa(
                    operacao.idPagamento(), true, "Falha definitiva no estorno: " + descricao, agora);
        } else {
            pagamentos.marcarFalhaTecnica(operacao.idPagamento(), descricao, agora);
            eventos.registrar(
                    PAGAMENTO_RECUSADO,
                    operacao.idCompra(),
                    operacao.idEmpresa(),
                    operacao.idCompra(),
                    ORIGEM,
                    new ResultadoPagamento(
                            operacao.idPagamento(),
                            null,
                            false,
                            "Pagamento indisponivel apos tentativas seguras",
                            StatusPagamento.FALHA_TECNICA.name(),
                            operacao.metodoPagamento(),
                            operacao.provedor(),
                            null,
                            null,
                            null));
        }
        registrarMetrica(operacao, "FALHA_DEFINITIVA");
    }

    @Transactional
    public int expirarPix() {
        Instant agora = relogio.instant();
        var expirados = pagamentos.bloquearPixExpirados(
                agora,
                propriedades.trabalhador().tamanhoLote());
        for (var pagamento : expirados) {
            pagamentos.expirarPix(pagamento.idPagamento(), "Prazo da cobranca PIX expirado", agora);
            eventos.registrar(
                    PAGAMENTO_RECUSADO,
                    pagamento.idCompra(),
                    pagamento.idEmpresa(),
                    pagamento.idCompra(),
                    ORIGEM,
                    new ResultadoPagamento(
                            pagamento.idPagamento(),
                            pagamento.txid(),
                            false,
                            "Prazo da cobranca PIX expirado",
                            StatusPagamento.EXPIRADO.name(),
                            pagamento.metodoPagamento(),
                            pagamento.provedor(),
                            pagamento.txid(),
                            null,
                            null));
        }
        if (!expirados.isEmpty()) {
            metricas.counter("orquestrapay.pix.expirados").increment(expirados.size());
        }
        return expirados.size();
    }

    private Duration calcularAtraso(int tentativas) {
        long multiplicador = 1L << Math.min(Math.max(tentativas - 1, 0), 20);
        Duration calculado = propriedades.trabalhador().atrasoInicial().multipliedBy(multiplicador);
        return calculado.compareTo(propriedades.trabalhador().atrasoMaximo()) > 0
                ? propriedades.trabalhador().atrasoMaximo()
                : calculado;
    }

    private String descrever(Throwable falha) {
        String tipo = falha == null ? "FalhaDesconhecida" : falha.getClass().getSimpleName();
        return "Falha tecnica do provedor (" + tipo + ")";
    }

    private void registrarMetrica(OperacaoPagamento operacao, String resultado) {
        metricas.counter(
                "orquestrapay.operacoes.pagamento",
                "tipo", operacao.tipo().name(),
                "resultado", resultado).increment();
    }
}
