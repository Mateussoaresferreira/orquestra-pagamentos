package br.com.orquestrapay.platform.event;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public class MetricasEventos {

    private static final Logger log = LoggerFactory.getLogger(MetricasEventos.class);

    private final RepositorioEventos repositorio;
    private final AtomicLong pendentes = new AtomicLong();
    private final AtomicLong quarentena = new AtomicLong();
    private final AtomicReference<Double> idadeMaisAntigaSegundos = new AtomicReference<>(0.0);
    private final Counter publicados;
    private final Counter falhasPublicacao;
    private final Counter descartados;
    private final Counter enviadosDlt;

    public MetricasEventos(
            RepositorioEventos repositorio,
            MeterRegistry registro,
            String topico) {
        this.repositorio = repositorio;

        Gauge.builder("orquestrapay.outbox.pendentes", pendentes, AtomicLong::get)
                .description("Eventos aguardando publicacao no outbox")
                .register(registro);
        Gauge.builder("orquestrapay.outbox.quarentena", quarentena, AtomicLong::get)
                .description("Eventos descartados e mantidos em quarentena")
                .register(registro);
        Gauge.builder(
                        "orquestrapay.outbox.idade.mais.antiga.segundos",
                        idadeMaisAntigaSegundos,
                        AtomicReference::get)
                .description("Idade em segundos do evento pendente mais antigo")
                .register(registro);

        publicados = contador(registro, "orquestrapay.outbox.publicados", topico,
                "Eventos publicados a partir do outbox");
        falhasPublicacao = contador(registro, "orquestrapay.outbox.falhas", topico,
                "Falhas ao publicar eventos do outbox");
        descartados = contador(registro, "orquestrapay.outbox.descartados", topico,
                "Eventos movidos para a quarentena do outbox");
        enviadosDlt = contador(registro, "orquestrapay.dlt.eventos", topico + ".dlt",
                "Eventos encaminhados para o topico de mensagens mortas");
    }

    @Scheduled(fixedDelayString = "${orquestrapay.eventos.intervalo-metricas:10000}")
    public void atualizarOutbox() {
        try {
            var resumo = repositorio.resumir();
            pendentes.set(resumo.pendentes());
            quarentena.set(resumo.quarentena());
            idadeMaisAntigaSegundos.set(resumo.idadeMaisAntigaSegundos());
        } catch (RuntimeException excecao) {
            log.debug("Nao foi possivel atualizar as metricas do outbox", excecao);
        }
    }

    public void registrarPublicacao() {
        publicados.increment();
    }

    public void registrarFalhaPublicacao() {
        falhasPublicacao.increment();
    }

    public void registrarDescarte() {
        descartados.increment();
    }

    public void registrarEnvioDlt() {
        enviadosDlt.increment();
    }

    private Counter contador(
            MeterRegistry registro,
            String nome,
            String topico,
            String descricao) {
        return Counter.builder(nome)
                .description(descricao)
                .tag("topico", topico)
                .register(registro);
    }
}
