package br.com.orquestrapay.inventory.messaging;

import static br.com.orquestrapay.contracts.TiposEventos.LIBERAR_ESTOQUE;
import static br.com.orquestrapay.contracts.TiposEventos.RESERVAR_ESTOQUE;
import static br.com.orquestrapay.contracts.VersoesEventos.VERSAO_INICIAL;
import static br.com.orquestrapay.contracts.VersoesEventos.exigirSuportada;

import br.com.orquestrapay.contracts.EventoSaga;
import br.com.orquestrapay.inventory.service.ServicoEstoque;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class ConsumidorComandosEstoque {

    private final ServicoEstoque estoque;

    public ConsumidorComandosEstoque(ServicoEstoque estoque) {
        this.estoque = estoque;
    }

    @KafkaListener(topics = "${orquestrapay.eventos.topicos.estoque}")
    public void receber(EventoSaga evento) {
        switch (evento.getTipo()) {
            case RESERVAR_ESTOQUE -> {
                exigirSuportada(evento, VERSAO_INICIAL);
                estoque.reservar(evento);
            }
            case LIBERAR_ESTOQUE -> {
                exigirSuportada(evento, VERSAO_INICIAL);
                estoque.liberar(evento);
            }
            default -> {
                // Cada consumidor ignora eventos que pertencem a outro contexto.
            }
        }
    }
}
