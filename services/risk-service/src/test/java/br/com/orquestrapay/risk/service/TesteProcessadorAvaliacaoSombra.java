package br.com.orquestrapay.risk.service;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import br.com.orquestrapay.risk.domain.ContextoRisco;
import br.com.orquestrapay.risk.domain.ResultadoAvaliacaoRisco;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TesteProcessadorAvaliacaoSombra {

    @Mock private ServicoComparacaoModelosRisco comparacao;
    @Mock private MetricasModelosRisco metricas;

    @Test
    void deveIsolarFalhaDoChallenger() {
        var solicitacao = new SolicitacaoAvaliacaoSombra(
                UUID.randomUUID(),
                UUID.randomUUID(),
                new ContextoRisco(new BigDecimal("100.00"), "BR", 0, 0),
                new ResultadoAvaliacaoRisco(
                        "regras", "1.0.0", 0, true, List.of(), "Sem sinais"));
        doThrow(new IllegalStateException("falha simulada"))
                .when(comparacao).comparar(solicitacao);
        var processador = new ProcessadorAvaliacaoSombra(comparacao, metricas);

        processador.processar(solicitacao);

        verify(metricas).registrarFalhaSombra();
    }
}
