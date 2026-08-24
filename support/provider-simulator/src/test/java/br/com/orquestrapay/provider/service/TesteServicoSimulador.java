package br.com.orquestrapay.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;

import br.com.orquestrapay.provider.api.PedidoAutorizacao;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class TesteServicoSimulador {

    private final ServicoSimulador servico = new ServicoSimulador();

    @Test
    void deveAutorizarPagamentoERepetirAMesmaResposta() {
        var pedido = pedido("tok_aprovado");

        var primeiraResposta = servico.autorizar(pedido);
        var segundaResposta = servico.autorizar(pedido);

        assertThat(primeiraResposta.aprovada()).isTrue();
        assertThat(primeiraResposta.idAutorizacao()).startsWith("aut_");
        assertThat(segundaResposta).isEqualTo(primeiraResposta);
    }

    @Test
    void deveRecusarTokenConfiguradoParaRecusa() {
        var resposta = servico.autorizar(pedido("tok_recusado"));

        assertThat(resposta.aprovada()).isFalse();
        assertThat(resposta.idAutorizacao()).isNull();
        assertThat(resposta.motivo()).isEqualTo("Transacao recusada pelo emissor");
    }

    @Test
    void deveRecuperarDepoisDeDuasFalhasTemporarias() {
        var pedido = pedido("tok_instavel");

        assertThatThrownBy(() -> servico.autorizar(pedido))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(excecao -> assertThat(((ResponseStatusException) excecao).getStatusCode())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> servico.autorizar(pedido))
                .isInstanceOf(ResponseStatusException.class);

        assertThat(servico.autorizar(pedido).aprovada()).isTrue();
    }

    @Test
    void deveEstornarUmaUnicaVezMesmoComRepeticao() {
        UUID idPagamento = UUID.randomUUID();

        var primeiroEstorno = servico.estornar(idPagamento);
        var segundoEstorno = servico.estornar(idPagamento);

        assertThat(primeiroEstorno.estornado()).isTrue();
        assertThat(primeiroEstorno.protocolo()).startsWith("est_");
        assertThat(segundoEstorno).isEqualTo(primeiroEstorno);
    }

    private PedidoAutorizacao pedido(String token) {
        return new PedidoAutorizacao(UUID.randomUUID(), new BigDecimal("149.90"), "BRL", token);
    }
}
