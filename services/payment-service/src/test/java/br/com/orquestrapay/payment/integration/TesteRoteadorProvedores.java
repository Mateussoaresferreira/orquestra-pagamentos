package br.com.orquestrapay.payment.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import br.com.orquestrapay.contracts.MetodoPagamento;
import br.com.orquestrapay.payment.api.PedidoAutorizacaoProvedor;
import br.com.orquestrapay.payment.api.RespostaAutorizacaoProvedor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TesteRoteadorProvedores {

    private ClienteProvedor principal;
    private ClienteProvedor contingencia;
    private RoteadorProvedores roteador;

    @BeforeEach
    void preparar() {
        principal = mock(ClienteProvedor.class);
        contingencia = mock(ClienteProvedor.class);
        configurar(principal, "principal", 10);
        configurar(contingencia, "contingencia", 20);
        roteador = new RoteadorProvedores(
                new CatalogoProvedores(Map.of(
                        "principal", principal,
                        "contingencia", contingencia)),
                new SimpleMeterRegistry());
    }

    @Test
    void deveUsarContingenciaQuandoPrincipalFalharTecnicamente() {
        var pedido = pedido();
        when(principal.autorizar(pedido)).thenThrow(new ExcecaoComunicacaoProvedor(
                "principal",
                "autorizar",
                NaturezaFalhaProvedor.SEGURA_PARA_FALLBACK,
                new RuntimeException("indisponivel antes do envio")));
        when(contingencia.autorizar(pedido)).thenReturn(new RespostaAutorizacaoProvedor(
                true, "aut-contingencia", "Autorizado"));

        var resultado = roteador.autorizar(pedido, null);

        assertThat(resultado.provedor()).isEqualTo("contingencia");
        assertThat(resultado.provedoresTentados()).containsExactly("principal", "contingencia");
        assertThat(resultado.resposta().aprovada()).isTrue();
    }

    @Test
    void naoDeveTentarOutroProvedorQuandoOResultadoForAmbiguo() {
        var pedido = pedido();
        var falha = new ExcecaoComunicacaoProvedor(
                "principal", "autorizar", new RuntimeException("resposta perdida"));
        when(principal.autorizar(pedido)).thenThrow(falha);

        assertThatThrownBy(() -> roteador.autorizar(pedido, null))
                .isSameAs(falha);

        verify(contingencia, never()).autorizar(any());
    }

    @Test
    void naoDeveTrocarProvedorDepoisQueUmaTentativaFoiFixada() {
        var pedido = pedido();
        var falha = new ExcecaoComunicacaoProvedor(
                "principal",
                "autorizar",
                NaturezaFalhaProvedor.SEGURA_PARA_FALLBACK,
                new RuntimeException("indisponivel"));
        when(principal.autorizar(pedido)).thenThrow(falha);

        assertThatThrownBy(() -> roteador.autorizar(pedido, "principal"))
                .isSameAs(falha);

        verify(contingencia, never()).autorizar(any());
    }

    @Test
    void naoDeveFazerFallbackQuandoEmissorRecusarPagamento() {
        var pedido = pedido();
        when(principal.autorizar(pedido)).thenReturn(new RespostaAutorizacaoProvedor(
                false, null, "Recusado pelo emissor"));

        var resultado = roteador.autorizar(pedido, null);

        assertThat(resultado.provedor()).isEqualTo("principal");
        assertThat(resultado.resposta().aprovada()).isFalse();
        verify(contingencia, never()).autorizar(any());
    }

    @Test
    void naoDeveFazerFallbackQuandoARequisicaoForInvalida() {
        var pedido = pedido();
        when(principal.autorizar(pedido)).thenThrow(new ExcecaoRequisicaoProvedor(422));

        org.junit.jupiter.api.Assertions.assertThrows(
                ExcecaoRequisicaoProvedor.class,
                () -> roteador.autorizar(pedido, null));

        verify(contingencia, never()).autorizar(any());
    }

    private void configurar(ClienteProvedor provedor, String nome, int prioridade) {
        when(provedor.nome()).thenReturn(nome);
        when(provedor.prioridade()).thenReturn(prioridade);
        when(provedor.aceita(MetodoPagamento.CARTAO)).thenReturn(true);
    }

    private PedidoAutorizacaoProvedor pedido() {
        return new PedidoAutorizacaoProvedor(
                UUID.randomUUID(),
                new BigDecimal("199.90"),
                "BRL",
                "tok_aprovado",
                2);
    }
}
