package br.com.orquestrapay.notification.service;

import java.util.concurrent.Executors;

import br.com.orquestrapay.notification.config.PropriedadesNotificacoes;
import br.com.orquestrapay.notification.data.RepositorioNotificacoes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TrabalhadorNotificacoes {

    private static final Logger log = LoggerFactory.getLogger(TrabalhadorNotificacoes.class);

    private final ServicoFilaNotificacoes fila;
    private final PropriedadesNotificacoes propriedades;

    public TrabalhadorNotificacoes(
            ServicoFilaNotificacoes fila,
            PropriedadesNotificacoes propriedades) {
        this.fila = fila;
        this.propriedades = propriedades;
    }

    @Scheduled(fixedDelayString = "${orquestrapay.notificacoes.intervalo:250}")
    public void enviarPendentes() {
        var lote = fila.reivindicar();
        if (lote.isEmpty()) {
            return;
        }
        log.debug("Processando lote de {} notificacoes", lote.size());
        var fabrica = Thread.ofVirtual().name("notificacao-", 0).factory();
        try (var executor = Executors.newFixedThreadPool(propriedades.concorrencia(), fabrica)) {
            lote.forEach(notificacao -> executor.submit(() -> enviar(notificacao)));
        }
    }

    private void enviar(RepositorioNotificacoes.NotificacaoPendente notificacao) {
        try {
            log.debug(
                    "Notificacao simulada para {}: {} - {}",
                    notificacao.destinatario(),
                    notificacao.assunto(),
                    notificacao.mensagem());
            fila.confirmar(notificacao.idNotificacao());
        } catch (RuntimeException excecao) {
            fila.falhar(notificacao, excecao.getMessage());
        }
    }
}
