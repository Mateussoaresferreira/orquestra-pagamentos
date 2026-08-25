package br.com.orquestrapay.payment.messaging;

import static br.com.orquestrapay.contracts.TiposEventos.AUTORIZAR_PAGAMENTO;
import static br.com.orquestrapay.contracts.TiposEventos.ESTORNAR_PAGAMENTO;
import static br.com.orquestrapay.contracts.VersoesEventos.VERSAO_INICIAL;
import static br.com.orquestrapay.contracts.VersoesEventos.exigirSuportada;

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

    @KafkaListener(topics = "${orquestrapay.eventos.topicos.pagamento}")
    public void receber(EventoSaga evento) {
        switch (evento.getTipo()) {
            case AUTORIZAR_PAGAMENTO -> {
                exigirSuportada(evento, VERSAO_INICIAL);
                pagamentos.autorizar(evento);
            }
            case ESTORNAR_PAGAMENTO -> {
                exigirSuportada(evento, VERSAO_INICIAL);
                pagamentos.estornar(evento);
            }
            default -> {
                // Evento de outro contexto.
            }
        }
    }
}
