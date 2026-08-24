package br.com.orquestrapay.platform.event;

import java.time.Clock;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import br.com.orquestrapay.contracts.EventoSaga;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

public class PublicadorEventos {

    private static final Logger log = LoggerFactory.getLogger(PublicadorEventos.class);

    private final RepositorioEventos repositorio;
    private final KafkaTemplate<String, EventoSaga> kafka;
    private final PropriedadesEventos propriedades;
    private final Clock relogio;
    private final MetricasEventos metricas;

    public PublicadorEventos(
            RepositorioEventos repositorio,
            KafkaTemplate<String, EventoSaga> kafka,
            PropriedadesEventos propriedades,
            Clock relogio,
            MetricasEventos metricas) {
        this.repositorio = repositorio;
        this.kafka = kafka;
        this.propriedades = propriedades;
        this.relogio = relogio;
        this.metricas = metricas;
    }

    @Scheduled(fixedDelayString = "${orquestrapay.eventos.intervalo-publicacao:500}")
    @Transactional
    public void publicarPendentes() {
        if (!propriedades.habilitado()) {
            return;
        }

        for (EventoPendente pendente : repositorio.buscarPendentes(
                propriedades.tamanhoLote(),
                propriedades.maximoTentativas())) {
            publicar(pendente);
        }
    }

    private void publicar(EventoPendente pendente) {
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

            var registro = new ProducerRecord<String, EventoSaga>(
                    propriedades.topico(), pendente.idCompra().toString(), evento);
            if (pendente.traceparent() != null && !pendente.traceparent().isBlank()) {
                registro.headers().add(
                        "traceparent",
                        pendente.traceparent().getBytes(StandardCharsets.US_ASCII));
            }
            kafka.send(registro)
                    .get(propriedades.tempoLimitePublicacao().toMillis(), TimeUnit.MILLISECONDS);
            repositorio.marcarPublicado(pendente.idEvento(), relogio.instant());
            metricas.registrarPublicacao();
        } catch (InterruptedException excecao) {
            Thread.currentThread().interrupt();
            registrarFalha(pendente, "Publicacao interrompida");
        } catch (Exception excecao) {
            registrarFalha(pendente, excecao.getMessage());
            log.warn("Falha ao publicar o evento {} do tipo {}", pendente.idEvento(), pendente.tipo(), excecao);
        }
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
        repositorio.registrarFalha(pendente.idEvento(), erro, agora.plus(atraso), descartadoEm);
        metricas.registrarFalhaPublicacao();
        if (descartadoEm != null) {
            metricas.registrarDescarte();
            log.error("Evento {} foi enviado para quarentena apos {} tentativas", pendente.idEvento(), tentativaAtual);
        }
    }
}
