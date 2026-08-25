package br.com.orquestrapay.payment.service;

import java.util.concurrent.Executors;

import br.com.orquestrapay.payment.api.PedidoAutorizacaoProvedor;
import br.com.orquestrapay.payment.api.PedidoCobrancaPixProvedor;
import br.com.orquestrapay.payment.api.PedidoEstornoProvedor;
import br.com.orquestrapay.payment.config.PropriedadesPagamentos;
import br.com.orquestrapay.payment.domain.OperacaoPagamento;
import br.com.orquestrapay.payment.domain.TipoOperacaoPagamento;
import br.com.orquestrapay.payment.integration.RoteadorProvedores;
import br.com.orquestrapay.payment.integration.ExcecaoComunicacaoProvedor;
import br.com.orquestrapay.platform.security.ProtecaoTokenPagamento;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TrabalhadorPagamentos {

    private final ServicoFilaPagamentos fila;
    private final RoteadorProvedores roteador;
    private final ProtecaoTokenPagamento protecaoToken;
    private final PropriedadesPagamentos propriedades;

    public TrabalhadorPagamentos(
            ServicoFilaPagamentos fila,
            RoteadorProvedores roteador,
            ProtecaoTokenPagamento protecaoToken,
            PropriedadesPagamentos propriedades) {
        this.fila = fila;
        this.roteador = roteador;
        this.protecaoToken = protecaoToken;
        this.propriedades = propriedades;
    }

    @Scheduled(fixedDelayString = "${orquestrapay.pagamentos.trabalhador.intervalo:250}")
    public void processar() {
        var lote = fila.reivindicar();
        if (lote.isEmpty()) {
            return;
        }

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (OperacaoPagamento operacao : lote) {
                executor.submit(() -> processar(operacao));
            }
        }
    }

    @Scheduled(fixedDelayString = "${orquestrapay.pagamentos.trabalhador.intervalo-expiracao:5000}")
    public void expirarPix() {
        fila.expirarPix();
    }

    private void processar(OperacaoPagamento operacao) {
        try {
            switch (operacao.tipo()) {
                case AUTORIZAR_CARTAO -> autorizarCartao(operacao);
                case CRIAR_PIX -> criarPix(operacao);
                case ESTORNAR -> estornar(operacao);
            }
        } catch (ExcecaoComunicacaoProvedor falha) {
            if (!falha.permiteFallback()
                    && operacao.tipo() != TipoOperacaoPagamento.ESTORNAR) {
                fila.registrarResultadoAmbiguo(operacao, falha);
                return;
            }
            fila.registrarFalha(operacao, falha);
        } catch (RuntimeException falha) {
            fila.registrarFalha(operacao, falha);
        }
    }

    private void autorizarCartao(OperacaoPagamento operacao) {
        String token = protecaoToken.revelar(operacao.tokenProtegido(), operacao.idCompra());
        var resultado = roteador.autorizar(
                new PedidoAutorizacaoProvedor(
                        operacao.idCompra(),
                        operacao.valor(),
                        operacao.moeda(),
                        token,
                        operacao.parcelas()),
                operacao.provedor());
        fila.concluirCartao(
                operacao,
                resultado,
                protecaoToken.calcularImpressao("pagamento", token));
    }

    private void criarPix(OperacaoPagamento operacao) {
        int expiracaoSegundos = Math.toIntExact(propriedades.pix().expiracao().toSeconds());
        var resultado = roteador.criarPix(
                new PedidoCobrancaPixProvedor(
                        operacao.idCompra(),
                        operacao.valor(),
                        operacao.moeda(),
                        expiracaoSegundos,
                        propriedades.pix().urlNotificacao().toString()),
                operacao.provedor());
        fila.concluirPix(operacao, resultado);
    }

    private void estornar(OperacaoPagamento operacao) {
        var resultado = roteador.estornar(
                operacao.metodoPagamento(),
                new PedidoEstornoProvedor(operacao.idPagamento()),
                operacao.provedor());
        fila.concluirEstorno(operacao, resultado);
    }
}
