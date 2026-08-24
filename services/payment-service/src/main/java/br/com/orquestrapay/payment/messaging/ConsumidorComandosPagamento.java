package br.com.orquestrapay.payment.messaging;

import static br.com.orquestrapay.contracts.TiposEventos.AUTORIZAR_PAGAMENTO;
import static br.com.orquestrapay.contracts.TiposEventos.ESTORNAR_PAGAMENTO;

import br.com.orquestrapay.contracts.EventoSaga;
import br.com.orquestrapay.payment.service.ServicoPagamento;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class ConsumidorComandosPagamento {

    private final ServicoPagamento pagamentos;

    public ConsumidorComandosPagamento(ServicoPagamento pagamentos) {
        this.pagamentos = pagamentos;
    }

    @KafkaListener(topics = "${orquestrapay.eventos.topico}")
    public void receber(EventoSaga evento) {
        switch (evento.getTipo()) {
            case AUTORIZAR_PAGAMENTO -> pagamentos.autorizar(evento);
            case ESTORNAR_PAGAMENTO -> pagamentos.estornar(evento);
            default -> {
                // Evento de outro contexto.
            }
        }
    }
}
