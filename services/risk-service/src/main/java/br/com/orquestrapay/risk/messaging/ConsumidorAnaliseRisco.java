package br.com.orquestrapay.risk.messaging;

import static br.com.orquestrapay.contracts.TiposEventos.ANALISAR_RISCO;

import br.com.orquestrapay.contracts.EventoSaga;
import br.com.orquestrapay.risk.service.ServicoRisco;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class ConsumidorAnaliseRisco {

    private final ServicoRisco risco;

    public ConsumidorAnaliseRisco(ServicoRisco risco) {
        this.risco = risco;
    }

    @KafkaListener(topics = "${orquestrapay.eventos.topicos.risco}")
    public void receber(EventoSaga evento) {
        if (ANALISAR_RISCO.equals(evento.getTipo())) {
            risco.analisar(evento);
        }
    }
}
