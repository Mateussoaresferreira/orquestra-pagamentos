package br.com.orquestrapay.notification.messaging;

import static br.com.orquestrapay.contracts.TiposEventos.COMPRA_COMPENSADA;
import static br.com.orquestrapay.contracts.TiposEventos.COMPRA_CONCLUIDA;
import static br.com.orquestrapay.contracts.TiposEventos.COMPRA_RECUSADA;

import java.util.Set;

import br.com.orquestrapay.contracts.EventoSaga;
import br.com.orquestrapay.notification.service.ServicoNotificacao;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class ConsumidorComprasFinalizadas {

    private static final Set<String> EVENTOS = Set.of(
            COMPRA_CONCLUIDA,
            COMPRA_RECUSADA,
            COMPRA_COMPENSADA);

    private final ServicoNotificacao notificacoes;

    public ConsumidorComprasFinalizadas(ServicoNotificacao notificacoes) {
        this.notificacoes = notificacoes;
    }

    @KafkaListener(topics = "${orquestrapay.eventos.topicos.notificacao}")
    public void receber(EventoSaga evento) {
        if (EVENTOS.contains(evento.getTipo())) {
            notificacoes.agendar(evento);
        }
    }
}
