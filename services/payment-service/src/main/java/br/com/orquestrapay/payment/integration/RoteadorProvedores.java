package br.com.orquestrapay.payment.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import br.com.orquestrapay.contracts.MetodoPagamento;
import br.com.orquestrapay.payment.api.PedidoAutorizacaoProvedor;
import br.com.orquestrapay.payment.api.PedidoCobrancaPixProvedor;
import br.com.orquestrapay.payment.api.PedidoEstornoProvedor;
import br.com.orquestrapay.payment.api.RespostaAutorizacaoProvedor;
import br.com.orquestrapay.payment.api.RespostaCobrancaPixProvedor;
import br.com.orquestrapay.payment.api.RespostaEstornoProvedor;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class RoteadorProvedores {

    private final CatalogoProvedores catalogo;
    private final MeterRegistry metricas;

    public RoteadorProvedores(CatalogoProvedores catalogo, MeterRegistry metricas) {
        this.catalogo = catalogo;
        this.metricas = metricas;
    }

    public ResultadoRoteamento<RespostaAutorizacaoProvedor> autorizar(
            PedidoAutorizacaoProvedor pedido,
            String provedorPreferido) {
        return executar(
                MetodoPagamento.CARTAO,
                provedorPreferido,
                provedor -> provedor.autorizar(pedido));
    }

    public ResultadoRoteamento<RespostaCobrancaPixProvedor> criarPix(
            PedidoCobrancaPixProvedor pedido,
            String provedorPreferido) {
        return executar(
                MetodoPagamento.PIX,
                provedorPreferido,
                provedor -> provedor.criarPix(pedido));
    }

    public ResultadoRoteamento<RespostaEstornoProvedor> estornar(
            MetodoPagamento metodo,
            PedidoEstornoProvedor pedido,
            String provedorOriginal) {
        ClienteProvedor provedor = catalogo.buscar(provedorOriginal)
                .filter(cliente -> cliente.aceita(metodo))
                .orElseThrow(() -> new IllegalStateException(
                        "O provedor original do pagamento nao esta disponivel para estorno"));
        return new ResultadoRoteamento<>(
                provedor.nome(),
                provedor.estornar(pedido),
                List.of(provedor.nome()));
    }

    private <T> ResultadoRoteamento<T> executar(
            MetodoPagamento metodo,
            String provedorPreferido,
            Function<ClienteProvedor, T> chamada) {
        var candidatos = catalogo.ordenar(metodo, provedorPreferido);
        if (candidatos.isEmpty()) {
            throw new IllegalStateException("Nenhum provedor aceita o metodo " + metodo);
        }

        var tentados = new ArrayList<String>();
        RuntimeException ultimaFalha = null;
        for (ClienteProvedor provedor : candidatos) {
            tentados.add(provedor.nome());
            try {
                T resposta = chamada.apply(provedor);
                metricas.counter(
                        "orquestrapay.roteamento.resultados",
                        "metodo", metodo.name(),
                        "provedor", provedor.nome(),
                        "resultado", "selecionado").increment();
                return new ResultadoRoteamento<>(provedor.nome(), resposta, tentados);
            } catch (ExcecaoComunicacaoProvedor excecao) {
                ultimaFalha = excecao;
                metricas.counter(
                        "orquestrapay.roteamento.resultados",
                        "metodo", metodo.name(),
                        "provedor", provedor.nome(),
                        "resultado", "fallback").increment();
            }
        }
        throw new ExcecaoProvedoresIndisponiveis(tentados, ultimaFalha);
    }
}
