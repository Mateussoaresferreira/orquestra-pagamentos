package br.com.orquestrapay.provider.service;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import br.com.orquestrapay.provider.api.PedidoAutorizacao;
import br.com.orquestrapay.provider.api.RespostaAutorizacao;
import br.com.orquestrapay.provider.api.RespostaEstorno;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ServicoSimulador {

    private final ConcurrentHashMap<UUID, AtomicInteger> tentativas = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, RespostaAutorizacao> autorizacoes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, RespostaEstorno> estornos = new ConcurrentHashMap<>();

    public RespostaAutorizacao autorizar(PedidoAutorizacao pedido) {
        var existente = autorizacoes.get(pedido.idCompra());
        if (existente != null) {
            return existente;
        }

        if ("tok_instavel".equals(pedido.tokenPagamento())) {
            int numero = tentativas
                    .computeIfAbsent(pedido.idCompra(), chave -> new AtomicInteger())
                    .incrementAndGet();
            if (numero <= 2) {
                throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Indisponibilidade temporaria simulada");
            }
        }

        RespostaAutorizacao resposta = switch (pedido.tokenPagamento()) {
            case "tok_recusado" -> new RespostaAutorizacao(
                    false,
                    null,
                    "Transacao recusada pelo emissor");
            default -> new RespostaAutorizacao(
                    true,
                    "aut_" + UUID.randomUUID().toString().replace("-", ""),
                    "Pagamento autorizado");
        };
        autorizacoes.putIfAbsent(pedido.idCompra(), resposta);
        return autorizacoes.get(pedido.idCompra());
    }

    public RespostaEstorno estornar(UUID idPagamento) {
        return estornos.computeIfAbsent(idPagamento, chave -> new RespostaEstorno(
                true,
                "est_" + UUID.randomUUID().toString().replace("-", "")));
    }
}
