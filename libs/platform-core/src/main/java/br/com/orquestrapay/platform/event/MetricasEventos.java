package br.com.orquestrapay.platform.event;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;

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
    private final MeterRegistry registro;
    private final ConcurrentHashMap<String, Counter> contadores = new ConcurrentHashMap<>();

    public MetricasEventos(
            RepositorioEventos repositorio,
            MeterRegistry registro) {
        this.repositorio = repositorio;
        this.registro = registro;

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

    public void registrarPublicacao(String topico) {
        contador("orquestrapay.outbox.publicados", topico,
                "Eventos publicados a partir do outbox").increment();
    }

    public void registrarFalhaPublicacao(String topico) {
        contador("orquestrapay.outbox.falhas", topico,
                "Falhas ao publicar eventos do outbox").increment();
    }

    public void registrarDescarte(String topico) {
        contador("orquestrapay.outbox.descartados", topico,
                "Eventos movidos para a quarentena do outbox").increment();
    }

    public void registrarEnvioDlt(String topico) {
        contador("orquestrapay.dlt.eventos", topico,
                "Eventos encaminhados para o topico de mensagens mortas").increment();
    }

    private Counter contador(
            String nome,
            String topico,
            String descricao) {
        return contadores.computeIfAbsent(nome + '|' + topico, ignorada -> Counter.builder(nome)
                .description(descricao)
                .tag("topico", topico)
                .register(registro));
    }
}
