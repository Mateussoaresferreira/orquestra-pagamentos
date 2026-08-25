package br.com.orquestrapay.provider.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import br.com.orquestrapay.provider.service.ServicoSimulador;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class TesteControladorSimulador {

    @Test
    void deveDeclararQueARequisicaoNaoFoiProcessadaAntesDePermitirFallback() {
        var controlador = new ControladorSimulador(mock(ServicoSimulador.class));

        var resposta = controlador.indisponibilidadeConfirmada();

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(resposta.getHeaders().getFirst("X-Orquestra-Resultado"))
                .isEqualTo("NAO_PROCESSADA");
    }

    @Test
    void naoDeveAutorizarFallbackQuandoARespostaForPerdida() {
        var controlador = new ControladorSimulador(mock(ServicoSimulador.class));

        var resposta = controlador.respostaPerdida();

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(resposta.getHeaders().getFirst("X-Orquestra-Resultado")).isNull();
    }
}
