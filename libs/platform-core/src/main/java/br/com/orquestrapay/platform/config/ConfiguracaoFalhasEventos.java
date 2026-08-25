package br.com.orquestrapay.platform.config;

import br.com.orquestrapay.contracts.EventoSaga;
import br.com.orquestrapay.platform.event.MetricasEventos;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@AutoConfiguration(after = ConfiguracaoEventos.class)
public class ConfiguracaoFalhasEventos {

    @Bean
    DefaultErrorHandler tratadorFalhasEventos(
            KafkaTemplate<String, EventoSaga> kafka,
            MetricasEventos metricas) {
        var recuperador = new DeadLetterPublishingRecoverer(
                kafka,
                (registro, excecao) -> {
                    String topicoDlt = registro.topic() + ".dlt";
                    metricas.registrarEnvioDlt(topicoDlt);
                    return new TopicPartition(
                            topicoDlt,
                            registro.partition());
                });
        var espera = new ExponentialBackOff(500, 2.0);
        espera.setMaxInterval(5_000);
        espera.setMaxElapsedTime(15_000);
        return new DefaultErrorHandler(recuperador, espera);
    }
}
