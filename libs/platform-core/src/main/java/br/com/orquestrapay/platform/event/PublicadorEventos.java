package br.com.orquestrapay.platform.event;

import java.time.Clock;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import br.com.orquestrapay.contracts.EventoSaga;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;

public class PublicadorEventos {

    private static final Logger log = LoggerFactory.getLogger(PublicadorEventos.class);

    private final ServicoFilaEventos fila;
    private final KafkaTemplate<String, EventoSaga> kafka;
    private final PropriedadesEventos propriedades;
    private final RoteadorTopicosEventos roteador;
    private final Clock relogio;
    private final MetricasEventos metricas;

    public PublicadorEventos(
            ServicoFilaEventos fila,
            KafkaTemplate<String, EventoSaga> kafka,
            PropriedadesEventos propriedades,
            RoteadorTopicosEventos roteador,
            Clock relogio,
            MetricasEventos metricas) {
        this.fila = fila;
        this.kafka = kafka;
        this.propriedades = propriedades;
        this.roteador = roteador;
        this.relogio = relogio;
        this.metricas = metricas;
    }

    @Scheduled(fixedDelayString = "${orquestrapay.eventos.intervalo-publicacao:500}")
    public void publicarPendentes() {
        if (!propriedades.habilitado()) {
            return;
        }

        var pendentes = fila.reivindicar();
        if (pendentes.isEmpty()) {
            return;
        }

        var eventosPorCompra = pendentes.stream()
                .collect(Collectors.groupingBy(
                        EventoPendente::idCompra,
                        LinkedHashMap::new,
                        Collectors.toList()));
        var publicados = new ArrayList<EventoPendente>(pendentes.size());
        var fabrica = Thread.ofVirtual().name("publicador-outbox-", 0).factory();
        try (var executor = Executors.newFixedThreadPool(
                propriedades.concorrenciaPublicacao(),
                fabrica)) {
            var tarefas = eventosPorCompra.values().stream()
                    .map(eventos -> executor.submit(() -> publicarEmOrdem(eventos)))
                    .toList();
            for (var tarefa : tarefas) {
                try {
                    publicados.addAll(tarefa.get());
                } catch (InterruptedException excecao) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception excecao) {
                    log.error("Falha inesperada no trabalhador de publicacao", excecao);
                }
            }
        }

        int confirmados = fila.confirmar(publicados);
        if (confirmados != publicados.size()) {
            log.warn(
                    "A posse de {} eventos expirou antes da confirmacao em lote",
                    publicados.size() - confirmados);
        }
    }

    private List<EventoPendente> publicarEmOrdem(List<EventoPendente> eventos) {
        var publicados = new ArrayList<EventoPendente>(eventos.size());
        for (EventoPendente evento : eventos.stream()
                .sorted(Comparator.comparingLong(EventoPendente::ordem))
                .toList()) {
            if (!publicar(evento)) {
                break;
            }
            publicados.add(evento);
        }
        return publicados;
    }

    private boolean publicar(EventoPendente pendente) {
        try {
            EventoSaga evento = EventoSaga.newBuilder()
                    .setIdEvento(pendente.idEvento().toString())
                    .setTipo(pendente.tipo())
                    .setVersao(pendente.versao())
                    .setIdCorrelacao(pendente.idCorrelacao().toString())
                    .setIdEmpresa(pendente.idEmpresa().toString())
                    .setIdCompra(pendente.idCompra().toString())
                    .setOrigem(pendente.origem())
                    .setOcorridoEm(pendente.ocorridoEm())
                    .setSequencia(pendente.ordem())
                    .setConteudo(pendente.conteudo())
                    .setTraceparent(pendente.traceparent())
                    .build();

            String topico = roteador.destino(pendente.tipo());
            var registro = new ProducerRecord<String, EventoSaga>(
                    topico, pendente.idCompra().toString(), evento);
            if (pendente.traceparent() != null && !pendente.traceparent().isBlank()) {
                registro.headers().add(
                        "traceparent",
                        pendente.traceparent().getBytes(StandardCharsets.US_ASCII));
            }
            kafka.send(registro)
                    .get(propriedades.tempoLimitePublicacao().toMillis(), TimeUnit.MILLISECONDS);
            metricas.registrarPublicacao(topico);
            return true;
        } catch (InterruptedException excecao) {
            Thread.currentThread().interrupt();
            registrarFalha(pendente, "Publicacao interrompida");
        } catch (Exception excecao) {
            registrarFalha(pendente, excecao.getMessage());
            log.warn("Falha ao publicar o evento {} do tipo {}", pendente.idEvento(), pendente.tipo(), excecao);
        }
        return false;
    }

    private void registrarFalha(EventoPendente pendente, String erro) {
        int tentativaAtual = pendente.tentativas() + 1;
        long multiplicador = 1L << Math.min(pendente.tentativas(), 10);
        var atrasoCalculado = propriedades.atrasoBase().multipliedBy(multiplicador);
        var atraso = atrasoCalculado.compareTo(propriedades.atrasoMaximo()) > 0
                ? propriedades.atrasoMaximo()
                : atrasoCalculado;
        var agora = relogio.instant();
        var descartadoEm = tentativaAtual >= propriedades.maximoTentativas() ? agora : null;
        if (!fila.registrarFalha(pendente, erro, agora.plus(atraso), descartadoEm)) {
            log.warn("A posse do evento {} expirou antes do registro da falha", pendente.idEvento());
            return;
        }
        String topico = roteador.destinoOuDesconhecido(pendente.tipo());
        metricas.registrarFalhaPublicacao(topico);
        if (descartadoEm != null) {
            metricas.registrarDescarte(topico);
            log.error("Evento {} foi enviado para quarentena apos {} tentativas", pendente.idEvento(), tentativaAtual);
        }
    }
}
