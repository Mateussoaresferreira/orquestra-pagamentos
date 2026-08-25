package br.com.orquestrapay.checkout.messaging;

import static br.com.orquestrapay.contracts.TiposEventos.ESTOQUE_LIBERADO;
import static br.com.orquestrapay.contracts.TiposEventos.ESTOQUE_RECUSADO;
import static br.com.orquestrapay.contracts.TiposEventos.ESTOQUE_RESERVADO;
import static br.com.orquestrapay.contracts.TiposEventos.LANCAMENTOS_RECUSADOS;
import static br.com.orquestrapay.contracts.TiposEventos.LANCAMENTOS_REGISTRADOS;
import static br.com.orquestrapay.contracts.TiposEventos.PAGAMENTO_AUTORIZADO;
import static br.com.orquestrapay.contracts.TiposEventos.PAGAMENTO_ESTORNADO;
import static br.com.orquestrapay.contracts.TiposEventos.PAGAMENTO_PENDENTE;
import static br.com.orquestrapay.contracts.TiposEventos.PAGAMENTO_RECUSADO;
import static br.com.orquestrapay.contracts.TiposEventos.RISCO_APROVADO;
import static br.com.orquestrapay.contracts.TiposEventos.RISCO_REPROVADO;
import static br.com.orquestrapay.contracts.VersoesEventos.VERSAO_INICIAL;
import static br.com.orquestrapay.contracts.VersoesEventos.exigirSuportada;

import java.util.Set;

import br.com.orquestrapay.checkout.service.ServicoCheckout;
import br.com.orquestrapay.contracts.EventoSaga;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class ConsumidorSaga {

    private static final Set<String> EVENTOS_TRATADOS = Set.of(
            ESTOQUE_RESERVADO,
            ESTOQUE_RECUSADO,
            RISCO_APROVADO,
            RISCO_REPROVADO,
            PAGAMENTO_AUTORIZADO,
            PAGAMENTO_PENDENTE,
            PAGAMENTO_RECUSADO,
            LANCAMENTOS_REGISTRADOS,
            LANCAMENTOS_RECUSADOS,
            PAGAMENTO_ESTORNADO,
            ESTOQUE_LIBERADO);

    private final ServicoCheckout checkout;

    public ConsumidorSaga(ServicoCheckout checkout) {
        this.checkout = checkout;
    }

    @KafkaListener(topics = "${orquestrapay.eventos.topicos.checkout}")
    public void receber(EventoSaga evento) {
        if (EVENTOS_TRATADOS.contains(evento.getTipo())) {
            exigirSuportada(evento, VERSAO_INICIAL);
            checkout.tratar(evento);
        }
    }
}
