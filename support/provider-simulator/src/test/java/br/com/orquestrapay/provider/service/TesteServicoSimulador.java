package br.com.orquestrapay.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

import br.com.orquestrapay.provider.api.PedidoAutorizacao;
import br.com.orquestrapay.provider.config.PropriedadesSimulador;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class TesteServicoSimulador {

    private final ServicoSimulador servico = new ServicoSimulador(
            new PropriedadesSimulador(
                    "principal",
                    "segredo-webhook-com-mais-de-vinte-quatro-caracteres",
                    Set.of("localhost")),
            RestClient.create(),
            new ObjectMapper(),
            new GeradorBrCodePix());

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
                .isInstanceOf(ExcecaoIndisponibilidadeConfirmada.class)
                .hasMessage("Indisponibilidade temporaria simulada");
        assertThatThrownBy(() -> servico.autorizar(pedido))
                .isInstanceOf(ExcecaoIndisponibilidadeConfirmada.class);

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

    @Test
    void deveSimularRespostaPerdidaSemDuplicarAAutorizacao() {
        var pedido = pedido("tok_resposta_perdida");
        var resposta = servico.autorizar(pedido);

        assertThat(servico.deveOcultarResposta(pedido)).isTrue();
        assertThat(servico.deveOcultarResposta(pedido)).isTrue();
        assertThat(servico.deveOcultarResposta(pedido)).isTrue();
        assertThat(servico.deveOcultarResposta(pedido)).isFalse();
        assertThat(servico.autorizar(pedido)).isEqualTo(resposta);
        assertThat(servico.consultarAutorizacao(pedido.idCompra())).contains(resposta);
    }

    private PedidoAutorizacao pedido(String token) {
        return new PedidoAutorizacao(UUID.randomUUID(), new BigDecimal("149.90"), "BRL", token);
    }
}
