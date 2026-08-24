package br.com.orquestrapay.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import br.com.orquestrapay.payment.api.RespostaPagamento;
import br.com.orquestrapay.payment.data.RepositorioPagamentos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TesteServicoConciliacao {

    @Mock
    private RepositorioPagamentos repositorio;

    @Test
    void deveConsultarOsPagamentosEmConjuntoERegistrarSomenteDivergencias() {
        UUID idEmpresa = UUID.randomUUID();
        UUID idPagamentoLocal = UUID.randomUUID();
        UUID idPagamentoAusente = UUID.randomUUID();
        Instant agora = Instant.parse("2026-08-23T12:00:00Z");
        var registroLocal = new RegistroProvedor(
                idPagamentoLocal, new BigDecimal("49.90"), "AUTORIZADO");
        var registroAusente = new RegistroProvedor(
                idPagamentoAusente, new BigDecimal("19.90"), "AUTORIZADO");
        var pagamentoLocal = new RespostaPagamento(
                idPagamentoLocal,
                UUID.randomUUID(),
                new BigDecimal("49.90"),
                "BRL",
                "AUTORIZADO",
                "aut-42",
                "Aprovado",
                agora);
        when(repositorio.buscarPorPagamentos(
                idEmpresa, List.of(idPagamentoLocal, idPagamentoAusente)))
                .thenReturn(Map.of(idPagamentoLocal, pagamentoLocal));

        var servico = new ServicoConciliacao(
                repositorio,
                Clock.fixed(agora, ZoneOffset.UTC));
        var resultado = servico.conciliar(
                idEmpresa,
                new PedidoConciliacao(List.of(registroLocal, registroAusente)));

        assertThat(resultado.registrosAnalisados()).isEqualTo(2);
        assertThat(resultado.divergenciasEncontradas()).isEqualTo(1);
        assertThat(resultado.divergencias()).singleElement().asString()
                .contains(idPagamentoAusente.toString());
        verify(repositorio).registrarDivergencia(
                eq(idEmpresa),
                eq(idPagamentoAusente),
                eq("AUSENTE_LOCALMENTE"),
                any(String.class),
                eq(agora));
    }
}
