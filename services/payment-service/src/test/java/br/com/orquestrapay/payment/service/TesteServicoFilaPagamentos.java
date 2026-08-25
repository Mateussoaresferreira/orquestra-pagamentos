package br.com.orquestrapay.payment.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import br.com.orquestrapay.contracts.MetodoPagamento;
import br.com.orquestrapay.payment.config.PropriedadesPagamentos;
import br.com.orquestrapay.payment.config.PropriedadesProvedor;
import br.com.orquestrapay.payment.data.RepositorioOperacoesPagamento;
import br.com.orquestrapay.payment.data.RepositorioPagamentos;
import br.com.orquestrapay.payment.domain.OperacaoPagamento;
import br.com.orquestrapay.payment.domain.TipoOperacaoPagamento;
import br.com.orquestrapay.payment.integration.ExcecaoComunicacaoProvedor;
import br.com.orquestrapay.platform.event.RegistroEventos;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TesteServicoFilaPagamentos {

    private static final Instant AGORA = Instant.parse("2026-08-25T12:00:00Z");

    @Mock private RepositorioOperacoesPagamento operacoes;
    @Mock private RepositorioPagamentos pagamentos;
    @Mock private RegistroEventos eventos;

    @Test
    void deveReagendarNoMesmoProvedorQuandoOResultadoForAmbiguo() {
        var operacao = operacao(1);
        var falha = new ExcecaoComunicacaoProvedor(
                "principal", "autorizar", new RuntimeException("timeout depois do envio"));
        when(operacoes.reagendar(eq(operacao), any(), any(), eq(AGORA))).thenReturn(true);

        servico(3).registrarResultadoAmbiguo(operacao, falha);

        verify(pagamentos).marcarConfirmacaoPendente(
                operacao.idPagamento(), "principal", mensagemAmbigua(), AGORA);
        verify(pagamentos).registrarTentativa(
                operacao.idPagamento(), "AUTORIZACAO", "RESULTADO_AMBIGUO", mensagemAmbigua(), AGORA);
        verify(operacoes).reagendar(eq(operacao), eq(AGORA.plusSeconds(1)), eq(mensagemAmbigua()), eq(AGORA));
        verify(pagamentos, never()).marcarFalhaTecnica(any(), any(), any());
        verifyNoEventoFinanceiro();
    }

    @Test
    void deveAbrirDivergenciaSemDeclararRecusaDepoisDoLimite() {
        var operacao = operacao(3);
        var falha = new ExcecaoComunicacaoProvedor(
                "principal", "autorizar", new RuntimeException("resposta perdida"));
        when(operacoes.marcarFalhaDefinitiva(operacao, mensagemAmbigua(), AGORA)).thenReturn(true);

        servico(3).registrarResultadoAmbiguo(operacao, falha);

        verify(pagamentos).marcarConfirmacaoPendente(
                operacao.idPagamento(), "principal", mensagemAmbigua(), AGORA);
        verify(pagamentos).registrarDivergencia(
                operacao.idEmpresa(),
                operacao.idPagamento(),
                "RESULTADO_AMBIGUO_PROVEDOR",
                mensagemAmbigua(),
                AGORA);
        verify(pagamentos, never()).marcarFalhaTecnica(any(), any(), any());
        verifyNoEventoFinanceiro();
    }

    private ServicoFilaPagamentos servico(int maximoTentativas) {
        return new ServicoFilaPagamentos(
                operacoes,
                pagamentos,
                eventos,
                propriedades(maximoTentativas),
                Clock.fixed(AGORA, ZoneOffset.UTC),
                new SimpleMeterRegistry());
    }

    private PropriedadesPagamentos propriedades(int maximoTentativas) {
        var provedor = new PropriedadesProvedor(
                URI.create("http://localhost:8090"),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                "chave-api-provedor-para-testes",
                "segredo-webhook-provedor-teste",
                10,
                Set.of(MetodoPagamento.CARTAO),
                10,
                100,
                Duration.ofSeconds(1));
        return new PropriedadesPagamentos(
                Map.of("principal", provedor),
                new PropriedadesPagamentos.Trabalhador(
                        10,
                        maximoTentativas,
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(1),
                        Duration.ofMinutes(1)),
                new PropriedadesPagamentos.Pix(
                        Duration.ofMinutes(15),
                        URI.create("http://localhost:8083/api/v1/webhooks/provedores"),
                        Duration.ofMinutes(5)),
                new PropriedadesPagamentos.ControleProvedores(false, true));
    }

    private OperacaoPagamento operacao(int tentativas) {
        return new OperacaoPagamento(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                TipoOperacaoPagamento.AUTORIZAR_CARTAO,
                MetodoPagamento.CARTAO,
                new BigDecimal("79.90"),
                "BRL",
                "v2:ativa:token-cifrado",
                1,
                null,
                tentativas,
                UUID.randomUUID());
    }

    private String mensagemAmbigua() {
        return "Resultado ambiguo no provedor principal; a operacao permanecera vinculada a ele ate confirmacao";
    }

    private void verifyNoEventoFinanceiro() {
        verify(eventos, never()).registrar(any(), any(), any(), any(), any(), any());
    }
}
