package br.com.orquestrapay.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import br.com.orquestrapay.notification.config.PropriedadesNotificacoes;
import br.com.orquestrapay.notification.data.RepositorioNotificacoes.NotificacaoPendente;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessagePreparator;

class TesteTrabalhadorNotificacoes {

    @Test
    void confirmaSomenteDepoisDoServidorSmtpAceitarAMensagem() throws Exception {
        var fila = mock(ServicoFilaNotificacoes.class);
        var correio = mock(JavaMailSender.class);
        var metricas = new SimpleMeterRegistry();
        var notificacao = notificacao();
        var mensagemCapturada = new AtomicReference<MimeMessage>();
        when(fila.reivindicar()).thenReturn(List.of(notificacao));
        when(fila.confirmar(notificacao)).thenReturn(true);
        doAnswer(invocacao -> {
            MimeMessagePreparator preparador = invocacao.getArgument(0);
            MimeMessage mensagem = new JavaMailSenderImpl().createMimeMessage();
            preparador.prepare(mensagem);
            mensagemCapturada.set(mensagem);
            return null;
        }).when(correio).send(any(MimeMessagePreparator.class));

        trabalhador(fila, correio, metricas).enviarPendentes();

        verify(correio).send(any(MimeMessagePreparator.class));
        verify(fila).confirmar(notificacao);
        verify(fila, never()).falhar(eq(notificacao), any());
        assertThat(mensagemCapturada.get().getAllRecipients()[0].toString())
                .isEqualTo(notificacao.destinatario());
        assertThat(mensagemCapturada.get().getSubject()).isEqualTo(notificacao.assunto());
        assertThat(mensagemCapturada.get().getHeader("Message-ID", null))
                .isEqualTo("<" + notificacao.idNotificacao() + "@orquestrapay.local>");
        assertThat(mensagemCapturada.get().getHeader("X-Orquestra-Compra-Id", null))
                .isEqualTo(notificacao.idCompra().toString());
        assertThat(metricas.get("orquestrapay.notificacoes.smtp")
                .tag("resultado", "enviada").counter().count()).isEqualTo(1);
    }

    @Test
    void mantemPendenteQuandoOTransporteSmtpFalha() {
        var fila = mock(ServicoFilaNotificacoes.class);
        var correio = mock(JavaMailSender.class);
        var metricas = new SimpleMeterRegistry();
        var notificacao = notificacao();
        when(fila.reivindicar()).thenReturn(List.of(notificacao));
        when(fila.falhar(eq(notificacao), any())).thenReturn(true);
        doThrow(new MailSendException("SMTP indisponivel"))
                .when(correio).send(any(MimeMessagePreparator.class));

        trabalhador(fila, correio, metricas).enviarPendentes();

        verify(fila).falhar(
                notificacao,
                "Falha de transporte SMTP (MailSendException)");
        verify(fila, never()).confirmar(notificacao);
        assertThat(metricas.get("orquestrapay.notificacoes.smtp")
                .tag("resultado", "falha").counter().count()).isEqualTo(1);
    }

    private TrabalhadorNotificacoes trabalhador(
            ServicoFilaNotificacoes fila,
            JavaMailSender correio,
            SimpleMeterRegistry metricas) {
        var propriedades = new PropriedadesNotificacoes(
                "nao-responda@orquestrapay.local",
                50,
                8,
                5,
                Duration.ofSeconds(30),
                Duration.ofSeconds(2),
                Duration.ofMinutes(10));
        return new TrabalhadorNotificacoes(fila, propriedades, correio, metricas);
    }

    private NotificacaoPendente notificacao() {
        return new NotificacaoPendente(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "cliente@exemplo.com",
                "Compra concluida",
                "Sua compra foi concluida com sucesso",
                1,
                UUID.randomUUID());
    }
}
