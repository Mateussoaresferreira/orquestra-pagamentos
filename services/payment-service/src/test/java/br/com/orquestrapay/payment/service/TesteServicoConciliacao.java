package br.com.orquestrapay.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import br.com.orquestrapay.payment.api.PedidoConciliacao;
import br.com.orquestrapay.payment.api.RegistroProvedor;
import br.com.orquestrapay.payment.data.RepositorioPagamentos;
import br.com.orquestrapay.platform.web.ExcecaoNegocio;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TesteServicoConciliacao {

    private static final Instant AGORA = Instant.parse("2026-08-23T12:00:00Z");
    private static final Instant INICIO = AGORA.minusSeconds(3_600);
    private static final Instant FIM = AGORA.plusSeconds(3_600);

    @Mock
    private RepositorioPagamentos repositorio;

    @Test
    void deveConciliarNosDoisSentidosEDetectarDuplicidade() {
        UUID idEmpresa = UUID.randomUUID();
        UUID idPagamentoLocal = UUID.randomUUID();
        UUID idPagamentoAusente = UUID.randomUUID();
        UUID idPagamentoSomenteLocal = UUID.randomUUID();
        UUID idConciliacao = UUID.randomUUID();
        var registroLocal = registro(idPagamentoLocal, "49.90", "aut-42");
        var registroAusente = registro(idPagamentoAusente, "19.90", "aut-ausente");
        var pedido = pedido(
                "extrato-001",
                List.of(registroLocal, registroLocal, registroAusente));
        var pagamentoLocal = pagamento(idPagamentoLocal, "49.90", "aut-42");
        var pagamentoSomenteLocal = pagamento(
                idPagamentoSomenteLocal, "29.90", "aut-somente-local");

        when(repositorio.iniciarConciliacao(
                eq(idEmpresa),
                eq("principal"),
                eq("extrato-001"),
                anyString(),
                eq("BRL"),
                eq(INICIO),
                eq(FIM),
                eq(3),
                eq(AGORA)))
                .thenAnswer(invocacao -> new RepositorioPagamentos.InicioConciliacao(
                        conciliacao(
                                idConciliacao,
                                invocacao.getArgument(3),
                                "PROCESSANDO",
                                3,
                                0,
                                0,
                                0,
                                0,
                                null),
                        true));
        when(repositorio.buscarConciliaveisPorIds(eq(idEmpresa), anyList()))
                .thenReturn(Map.of(idPagamentoLocal, pagamentoLocal));
        when(repositorio.buscarConciliaveisNaJanela(
                idEmpresa, "principal", "BRL", INICIO, FIM, 501))
                .thenReturn(List.of(pagamentoLocal, pagamentoSomenteLocal));

        var metricas = new SimpleMeterRegistry();
        var resultado = servico(metricas).conciliar(idEmpresa, pedido);

        assertThat(resultado.registrosProvedor()).isEqualTo(3);
        assertThat(resultado.registrosLocais()).isEqualTo(2);
        assertThat(resultado.registrosDuplicados()).isEqualTo(1);
        assertThat(resultado.registrosAnalisados()).isEqualTo(4);
        assertThat(resultado.divergenciasEncontradas()).isEqualTo(3);
        assertThat(resultado.divergencias())
                .anyMatch(item -> item.contains("REGISTRO_DUPLICADO_PROVEDOR"))
                .anyMatch(item -> item.contains("AUSENTE_LOCALMENTE"))
                .anyMatch(item -> item.contains("AUSENTE_NO_PROVEDOR"));
        assertThat(resultado.reaproveitada()).isFalse();
        verify(repositorio).concluirConciliacao(
                idConciliacao, 2, 1, 4, 3, AGORA);
        verify(repositorio).registrarDivergencia(
                eq(idEmpresa),
                eq(idPagamentoAusente),
                eq("AUSENTE_LOCALMENTE"),
                anyString(),
                eq(AGORA));
        verify(repositorio).registrarDivergencia(
                eq(idEmpresa),
                eq(idPagamentoSomenteLocal),
                eq("AUSENTE_NO_PROVEDOR"),
                anyString(),
                eq(AGORA));
        assertThat(metricas.counter(
                        "orquestrapay.conciliacoes.divergencias",
                        "tipo",
                        "REGISTRO_DUPLICADO_PROVEDOR").count())
                .isEqualTo(1);
    }

    @Test
    void deveReaproveitarOResultadoDoMesmoExtratoSemProcessarNovamente() {
        UUID idEmpresa = UUID.randomUUID();
        UUID idPagamento = UUID.randomUUID();
        UUID idConciliacao = UUID.randomUUID();
        var pedido = pedido("extrato-repetido", List.of(registro(idPagamento, "49.90", "aut-42")));
        when(repositorio.iniciarConciliacao(
                eq(idEmpresa),
                eq("principal"),
                eq("extrato-repetido"),
                anyString(),
                eq("BRL"),
                eq(INICIO),
                eq(FIM),
                eq(1),
                eq(AGORA)))
                .thenAnswer(invocacao -> new RepositorioPagamentos.InicioConciliacao(
                        conciliacao(
                                idConciliacao,
                                invocacao.getArgument(3),
                                "CONCLUIDA_COM_DIVERGENCIAS",
                                1,
                                0,
                                0,
                                1,
                                1,
                                AGORA),
                        false));
        when(repositorio.listarOcorrenciasConciliacao(idConciliacao))
                .thenReturn(List.of(new RepositorioPagamentos.OcorrenciaConciliacao(
                        idPagamento,
                        "AUSENTE_LOCALMENTE",
                        "Pagamento ausente",
                        AGORA)));

        var resultado = servico(new SimpleMeterRegistry()).conciliar(idEmpresa, pedido);

        assertThat(resultado.reaproveitada()).isTrue();
        assertThat(resultado.idConciliacao()).isEqualTo(idConciliacao);
        assertThat(resultado.divergencias()).containsExactly(
                "[AUSENTE_LOCALMENTE] Pagamento ausente");
        verify(repositorio, never()).buscarConciliaveisPorIds(any(), anyList());
        verify(repositorio, never()).buscarConciliaveisNaJanela(
                any(), anyString(), anyString(), any(), any(), anyInt());
        verify(repositorio, never()).concluirConciliacao(
                any(), anyInt(), anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    void deveRejeitarIdentificadorDeExtratoReutilizadoComOutroConteudo() {
        UUID idEmpresa = UUID.randomUUID();
        var pedido = pedido(
                "extrato-adulterado",
                List.of(registro(UUID.randomUUID(), "49.90", "aut-42")));
        when(repositorio.iniciarConciliacao(
                eq(idEmpresa),
                eq("principal"),
                eq("extrato-adulterado"),
                anyString(),
                eq("BRL"),
                eq(INICIO),
                eq(FIM),
                eq(1),
                eq(AGORA)))
                .thenReturn(new RepositorioPagamentos.InicioConciliacao(
                        conciliacao(
                                UUID.randomUUID(),
                                "0".repeat(64),
                                "CONCLUIDA",
                                1,
                                1,
                                0,
                                1,
                                0,
                                AGORA),
                        false));

        assertThatThrownBy(() -> servico(new SimpleMeterRegistry()).conciliar(idEmpresa, pedido))
                .isInstanceOf(ExcecaoNegocio.class)
                .hasMessageContaining("outro conteudo");
    }

    private ServicoConciliacao servico(SimpleMeterRegistry metricas) {
        return new ServicoConciliacao(
                repositorio,
                Clock.fixed(AGORA, ZoneOffset.UTC),
                metricas);
    }

    private PedidoConciliacao pedido(String identificador, List<RegistroProvedor> registros) {
        return new PedidoConciliacao(
                "principal",
                identificador,
                INICIO,
                FIM,
                "BRL",
                registros);
    }

    private RegistroProvedor registro(UUID idPagamento, String valor, String idTransacao) {
        return new RegistroProvedor(
                idPagamento,
                new BigDecimal(valor),
                "BRL",
                "AUTORIZADO",
                idTransacao,
                AGORA);
    }

    private RepositorioPagamentos.PagamentoConciliavel pagamento(
            UUID idPagamento,
            String valor,
            String idAutorizacao) {
        return new RepositorioPagamentos.PagamentoConciliavel(
                idPagamento,
                new BigDecimal(valor),
                "BRL",
                "AUTORIZADO",
                idAutorizacao,
                "principal",
                AGORA);
    }

    private RepositorioPagamentos.ConciliacaoPersistida conciliacao(
            UUID idConciliacao,
            String hash,
            String status,
            int registrosProvedor,
            int registrosLocais,
            int registrosDuplicados,
            int registrosAnalisados,
            int divergencias,
            Instant concluidaEm) {
        return new RepositorioPagamentos.ConciliacaoPersistida(
                idConciliacao,
                "principal",
                "extrato",
                hash,
                "BRL",
                INICIO,
                FIM,
                registrosProvedor,
                registrosLocais,
                registrosDuplicados,
                registrosAnalisados,
                divergencias,
                status,
                AGORA,
                concluidaEm);
    }
}
