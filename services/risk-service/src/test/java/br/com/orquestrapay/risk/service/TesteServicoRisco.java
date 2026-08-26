package br.com.orquestrapay.risk.service;

import static br.com.orquestrapay.contracts.TiposEventos.RISCO_APROVADO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import br.com.orquestrapay.contracts.EventoSaga;
import br.com.orquestrapay.contracts.ResultadoRisco;
import br.com.orquestrapay.contracts.SolicitacaoAnaliseRisco;
import br.com.orquestrapay.platform.event.RegistroEventos;
import br.com.orquestrapay.platform.event.RegistroMensagens;
import br.com.orquestrapay.platform.web.ExcecaoNegocio;
import br.com.orquestrapay.risk.data.RepositorioRisco;
import br.com.orquestrapay.risk.domain.ExperimentoModelosRisco;
import br.com.orquestrapay.risk.domain.ModeloRisco;
import br.com.orquestrapay.risk.domain.PoliticaRisco;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class TesteServicoRisco {

    private static final PoliticaRisco POLITICA = politicaPadrao();

    @Mock private RepositorioRisco repositorio;
    @Mock private RegistroEventos eventos;
    @Mock private RegistroMensagens mensagens;
    @Mock private ObjectMapper json;
    @Mock private ApplicationEventPublisher publicadorEventosAplicacao;
    @Mock private MetricasModelosRisco metricas;

    @Test
    void deveAprovarCompraSemSinaisDeRisco() throws Exception {
        UUID idEvento = UUID.randomUUID();
        UUID idEmpresa = UUID.randomUUID();
        UUID idCompra = UUID.randomUUID();
        Instant agora = Instant.parse("2026-08-23T12:00:00Z");
        var evento = evento(idEvento, idEmpresa, idCompra, "conteudo-risco");
        var solicitacao = new SolicitacaoAnaliseRisco(
                "cliente-42", new BigDecimal("199.90"), "BR", "dispositivo-42");

        when(mensagens.iniciar(idEvento, "risco-v1")).thenReturn(true);
        when(repositorio.existePorCompra(idEmpresa, idCompra)).thenReturn(false);
        when(json.readValue("conteudo-risco", SolicitacaoAnaliseRisco.class)).thenReturn(solicitacao);
        when(repositorio.contarComprasRecentes(
                idEmpresa,
                "cliente-42",
                agora.minus(Duration.ofMinutes(10)))).thenReturn(0);
        when(repositorio.contarClientesNoDispositivo(
                idEmpresa,
                "dispositivo-42",
                "cliente-42",
                agora.minus(Duration.ofHours(24)))).thenReturn(0);

        var servico = new ServicoRisco(
                repositorio,
                eventos,
                mensagens,
                json,
                Clock.fixed(agora, ZoneOffset.UTC),
                POLITICA,
                modelosComChallenger(),
                publicadorEventosAplicacao,
                metricas);
        servico.analisar(evento);

        verify(repositorio).bloquearJanelasDeVelocidade(
                idEmpresa,
                "cliente-42",
                "dispositivo-42");
        verify(repositorio).adicionar(
                idEmpresa,
                idCompra,
                "cliente-42",
                "dispositivo-42",
                new BigDecimal("199.90"),
                "BR",
                0,
                true,
                "Nenhum sinal de risco relevante",
                "regras-transacionais",
                "1.0.0",
                agora);
        verify(eventos).registrar(
                eq(RISCO_APROVADO),
                eq(idCompra),
                eq(idEmpresa),
                eq(idCompra),
                eq("servico-risco"),
                any(ResultadoRisco.class));
        verify(metricas).registrarAvaliacao(eq("CHAMPION"), any());
        verify(publicadorEventosAplicacao).publishEvent(any(SolicitacaoAvaliacaoSombra.class));
    }

    @Test
    void deveResponderNaoEncontradoParaEmpresaSemAnalise() {
        UUID idEmpresa = UUID.randomUUID();
        UUID idCompra = UUID.randomUUID();
        when(repositorio.buscar(idEmpresa, idCompra)).thenReturn(Optional.empty());
        var servico = new ServicoRisco(
                repositorio,
                eventos,
                mensagens,
                json,
                Clock.systemUTC(),
                POLITICA,
                modelosComChallenger(),
                publicadorEventosAplicacao,
                metricas);

        var excecao = catchThrowableOfType(
                ExcecaoNegocio.class,
                () -> servico.buscar(idEmpresa, idCompra));

        assertThat(excecao.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(excecao.codigo()).isEqualTo("analise-risco-nao-encontrada");
    }

    private EventoSaga evento(UUID idEvento, UUID idEmpresa, UUID idCompra, String conteudo) {
        var evento = new EventoSaga();
        evento.setIdEvento(idEvento.toString());
        evento.setIdEmpresa(idEmpresa.toString());
        evento.setIdCompra(idCompra.toString());
        evento.setConteudo(conteudo);
        return evento;
    }

    private static PoliticaRisco politicaPadrao() {
        return new PoliticaRisco(
                new BigDecimal("1000.00"),
                new BigDecimal("5000.00"),
                15,
                45,
                "BR",
                25,
                3,
                Duration.ofMinutes(10),
                35,
                3,
                Duration.ofHours(24),
                40,
                70);
    }

    private static ExperimentoModelosRisco modelosComChallenger() {
        var champion = new ModeloRisco("regras-transacionais", "1.0.0", POLITICA);
        var challenger = new ModeloRisco("regras-transacionais", "1.1.0", POLITICA);
        return new ExperimentoModelosRisco(champion, challenger, 100);
    }
}
