package br.com.orquestrapay.provider.api;

import br.com.orquestrapay.provider.service.ServicoSimulador;
import br.com.orquestrapay.provider.service.ExcecaoIndisponibilidadeConfirmada;
import br.com.orquestrapay.provider.service.ExcecaoRespostaPerdida;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api/v1")
public class ControladorSimulador {

    private final ServicoSimulador simulador;

    public ControladorSimulador(ServicoSimulador simulador) {
        this.simulador = simulador;
    }

    @PostMapping("/autorizacoes")
    @Operation(summary = "Simula aprovacao, recusa e indisponibilidade do adquirente")
    RespostaAutorizacao autorizar(@Valid @RequestBody PedidoAutorizacao pedido) {
        RespostaAutorizacao resposta = simulador.autorizar(pedido);
        if (simulador.deveOcultarResposta(pedido)) {
            throw new ExcecaoRespostaPerdida();
        }
        return resposta;
    }

    @GetMapping("/autorizacoes/compras/{idCompra}")
    @Operation(summary = "Consulta uma autorizacao pela compra para auditoria do simulador")
    ResponseEntity<RespostaAutorizacao> consultarAutorizacao(@PathVariable java.util.UUID idCompra) {
        return simulador.consultarAutorizacao(idCompra)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/estornos")
    @Operation(summary = "Estorna uma autorizacao de forma idempotente")
    RespostaEstorno estornar(@Valid @RequestBody PedidoEstorno pedido) {
        return simulador.estornar(pedido.idPagamento());
    }

    @PostMapping("/cobrancas/pix")
    @Operation(summary = "Cria uma cobranca PIX idempotente")
    RespostaCobrancaPix criarPix(@Valid @RequestBody PedidoCobrancaPix pedido) {
        return simulador.criarPix(pedido);
    }

    @PostMapping("/cobrancas/pix/{txid}/confirmacoes")
    @Operation(summary = "Confirma uma cobranca PIX e envia o webhook assinado")
    void confirmarPix(@PathVariable String txid) {
        simulador.confirmarPix(txid);
    }

    @ExceptionHandler(ExcecaoIndisponibilidadeConfirmada.class)
    ResponseEntity<Void> indisponibilidadeConfirmada() {
        return ResponseEntity.status(503)
                .header("X-Orquestra-Resultado", "NAO_PROCESSADA")
                .build();
    }

    @ExceptionHandler(ExcecaoRespostaPerdida.class)
    ResponseEntity<Void> respostaPerdida() {
        return ResponseEntity.status(504).build();
    }
}
