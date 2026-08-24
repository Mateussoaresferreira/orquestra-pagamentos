package br.com.orquestrapay.notification.service;

import java.time.Clock;

import br.com.orquestrapay.notification.data.RepositorioNotificacoes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class TrabalhadorNotificacoes {

    private static final Logger log = LoggerFactory.getLogger(TrabalhadorNotificacoes.class);

    private final RepositorioNotificacoes repositorio;
    private final Clock relogio;

    public TrabalhadorNotificacoes(RepositorioNotificacoes repositorio, Clock relogio) {
        this.repositorio = repositorio;
        this.relogio = relogio;
    }

    @Scheduled(fixedDelayString = "${orquestrapay.notificacoes.intervalo:1000}")
    @Transactional
    public void enviarPendentes() {
        for (var notificacao : repositorio.bloquearPendentes(20)) {
            try {
                log.info(
                        "Notificacao simulada para {}: {} - {}",
                        notificacao.destinatario(),
                        notificacao.assunto(),
                        notificacao.mensagem());
                repositorio.marcarEnviada(notificacao.idNotificacao(), relogio.instant());
            } catch (RuntimeException excecao) {
                repositorio.registrarFalha(notificacao.idNotificacao(), excecao.getMessage());
            }
        }
    }
}
