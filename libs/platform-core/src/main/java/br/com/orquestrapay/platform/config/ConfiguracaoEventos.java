package br.com.orquestrapay.platform.config;

import java.time.Clock;

import br.com.orquestrapay.contracts.EventoSaga;
import br.com.orquestrapay.platform.event.PropriedadesEventos;
import br.com.orquestrapay.platform.event.MetricasEventos;
import br.com.orquestrapay.platform.event.PublicadorEventos;
import br.com.orquestrapay.platform.event.RegistroEventos;
import br.com.orquestrapay.platform.event.RegistroMensagens;
import br.com.orquestrapay.platform.event.RepositorioEventos;
import io.micrometer.tracing.Tracer;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

@AutoConfiguration
@EnableScheduling
@EnableConfigurationProperties(PropriedadesEventos.class)
public class ConfiguracaoEventos {

    @Bean
    @ConditionalOnMissingBean
    Clock relogio() {
        return Clock.systemUTC();
    }

    @Bean
    RepositorioEventos repositorioEventos(JdbcClient banco) {
        return new RepositorioEventos(banco);
    }

    @Bean
    RegistroEventos registroEventos(
            RepositorioEventos repositorio,
            ObjectMapper json,
            Clock relogio,
            ObjectProvider<Tracer> provedorRastreador) {
        return new RegistroEventos(repositorio, json, relogio, provedorRastreador.getIfAvailable());
    }

    @Bean
    RegistroMensagens registroMensagens(JdbcClient banco, Clock relogio) {
        return new RegistroMensagens(banco, relogio);
    }

    @Bean
    MetricasEventos metricasEventos(
            RepositorioEventos repositorio,
            MeterRegistry registro,
            PropriedadesEventos propriedades) {
        return new MetricasEventos(repositorio, registro, propriedades.topico());
    }

    @Bean
    PublicadorEventos publicadorEventos(
            RepositorioEventos repositorio,
            KafkaTemplate<String, EventoSaga> kafka,
            PropriedadesEventos propriedades,
            Clock relogio,
            MetricasEventos metricas) {
        return new PublicadorEventos(repositorio, kafka, propriedades, relogio, metricas);
    }
}
