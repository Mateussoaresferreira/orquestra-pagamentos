package br.com.orquestrapay.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import br.com.orquestrapay.notification.config.PropriedadesWebhooks;
import br.com.orquestrapay.platform.web.ExcecaoNegocio;
import org.junit.jupiter.api.Test;

class TesteValidadorUrlWebhook {

    @Test
    void deveAceitarSomenteDestinoHttpsPublicoEmProducao() {
        var validador = new ValidadorUrlWebhook(propriedades(false));

        assertThat(validador.validar("https://8.8.8.8/eventos").toString())
                .isEqualTo("https://8.8.8.8/eventos");
        assertThatThrownBy(() -> validador.validar("http://8.8.8.8/eventos"))
                .isInstanceOf(ExcecaoNegocio.class)
                .extracting(excecao -> ((ExcecaoNegocio) excecao).codigo())
                .isEqualTo("url-webhook-nao-permitida");
    }

    @Test
    void deveBloquearDestinosLocaisEPrivadosEmProducao() {
        var validador = new ValidadorUrlWebhook(propriedades(false));

        assertThatThrownBy(() -> validador.validar("https://127.0.0.1/eventos"))
                .isInstanceOf(ExcecaoNegocio.class)
                .extracting(excecao -> ((ExcecaoNegocio) excecao).codigo())
                .isEqualTo("destino-webhook-privado");
        assertThatThrownBy(() -> validador.validar("https://10.20.30.40/eventos"))
                .isInstanceOf(ExcecaoNegocio.class)
                .extracting(excecao -> ((ExcecaoNegocio) excecao).codigo())
                .isEqualTo("destino-webhook-privado");
    }

    @Test
    void deveBloquearCredenciaisQueryEFragmento() {
        var validador = new ValidadorUrlWebhook(propriedades(false));

        assertThatThrownBy(() -> validador.validar("https://usuario:senha@8.8.8.8/eventos"))
                .isInstanceOf(ExcecaoNegocio.class);
        assertThatThrownBy(() -> validador.validar("https://8.8.8.8/eventos?token=segredo"))
                .isInstanceOf(ExcecaoNegocio.class);
        assertThatThrownBy(() -> validador.validar("https://8.8.8.8/eventos#interno"))
                .isInstanceOf(ExcecaoNegocio.class);
    }

    @Test
    void devePermitirHttpPrivadoSomenteQuandoAmbienteLocalAutorizar() {
        var validador = new ValidadorUrlWebhook(propriedades(true));

        assertThat(validador.validar("http://127.0.0.1:8092/webhooks").toString())
                .isEqualTo("http://127.0.0.1:8092/webhooks");
    }

    private PropriedadesWebhooks propriedades(boolean permitirPrivados) {
        return new PropriedadesWebhooks(
                20,
                5,
                Duration.ofSeconds(30),
                Duration.ofSeconds(5),
                Duration.ofHours(6),
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                permitirPrivados);
    }
}
