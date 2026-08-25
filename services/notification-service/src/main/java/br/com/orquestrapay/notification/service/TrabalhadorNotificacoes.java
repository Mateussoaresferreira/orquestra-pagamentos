package br.com.orquestrapay.notification.service;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

import br.com.orquestrapay.notification.config.PropriedadesNotificacoes;
import br.com.orquestrapay.notification.data.RepositorioNotificacoes;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TrabalhadorNotificacoes {

    private static final Logger log = LoggerFactory.getLogger(TrabalhadorNotificacoes.class);

    private final ServicoFilaNotificacoes fila;
    private final PropriedadesNotificacoes propriedades;
    private final JavaMailSender correio;
    private final MeterRegistry metricas;

    public TrabalhadorNotificacoes(
            ServicoFilaNotificacoes fila,
            PropriedadesNotificacoes propriedades,
            JavaMailSender correio,
            MeterRegistry metricas) {
        this.fila = fila;
        this.propriedades = propriedades;
        this.correio = correio;
        this.metricas = metricas;
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
            correio.send(mensagem -> {
                var auxiliar = new MimeMessageHelper(
                        mensagem,
                        false,
                        StandardCharsets.UTF_8.name());
                auxiliar.setFrom(propriedades.remetente());
                auxiliar.setTo(notificacao.destinatario());
                auxiliar.setSubject(notificacao.assunto());
                auxiliar.setText(notificacao.mensagem(), false);
                mensagem.setHeader(
                        "Message-ID",
                        "<" + notificacao.idNotificacao() + "@orquestrapay.local>");
                mensagem.setHeader(
                        "X-Orquestra-Notificacao-Id",
                        notificacao.idNotificacao().toString());
                mensagem.setHeader(
                        "X-Orquestra-Compra-Id",
                        notificacao.idCompra().toString());
            });
            if (fila.confirmar(notificacao)) {
                registrarMetrica("enviada");
                log.debug("Notificacao {} entregue ao servidor SMTP", notificacao.idNotificacao());
            } else {
                registrarMetrica("lease_perdido");
                log.warn("A posse da notificacao {} expirou apos o envio", notificacao.idNotificacao());
            }
        } catch (RuntimeException excecao) {
            String erro = "Falha de transporte SMTP (" + excecao.getClass().getSimpleName() + ")";
            if (fila.falhar(notificacao, erro)) {
                registrarMetrica("falha");
            } else {
                registrarMetrica("lease_perdido");
            }
            log.warn("Falha ao entregar a notificacao {} por SMTP", notificacao.idNotificacao());
        }
    }

    private void registrarMetrica(String resultado) {
        metricas.counter("orquestrapay.notificacoes.smtp", "resultado", resultado).increment();
    }
}
