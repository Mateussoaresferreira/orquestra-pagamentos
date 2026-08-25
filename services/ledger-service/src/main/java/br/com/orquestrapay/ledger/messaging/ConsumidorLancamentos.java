package br.com.orquestrapay.ledger.messaging;

import static br.com.orquestrapay.contracts.TiposEventos.REGISTRAR_LANCAMENTOS;

import br.com.orquestrapay.contracts.EventoSaga;
import br.com.orquestrapay.ledger.service.ServicoRazao;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class ConsumidorLancamentos {

    private final ServicoRazao razao;

    public ConsumidorLancamentos(ServicoRazao razao) {
        this.razao = razao;
    }

    @KafkaListener(topics = "${orquestrapay.eventos.topicos.razao}")
    public void receber(EventoSaga evento) {
        if (REGISTRAR_LANCAMENTOS.equals(evento.getTipo())) {
            razao.registrar(evento);
        }
    }
}
