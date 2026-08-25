package br.com.orquestrapay.platform.config;

import java.time.Clock;

import br.com.orquestrapay.contracts.EventoSaga;
import br.com.orquestrapay.platform.event.PropriedadesEventos;
import br.com.orquestrapay.platform.event.MetricasEventos;
import br.com.orquestrapay.platform.event.PublicadorEventos;
import br.com.orquestrapay.platform.event.RegistroEventos;
import br.com.orquestrapay.platform.event.RegistroMensagens;
import br.com.orquestrapay.platform.event.RepositorioEventos;
import br.com.orquestrapay.platform.event.ServicoFilaEventos;
import br.com.orquestrapay.platform.event.ControladorQuarentena;
import br.com.orquestrapay.platform.event.ServicoQuarentena;
import br.com.orquestrapay.platform.event.RoteadorTopicosEventos;
import br.com.orquestrapay.platform.event.PropriedadesRetencaoEventos;
import br.com.orquestrapay.platform.event.RepositorioRetencaoEventos;
import br.com.orquestrapay.platform.event.ServicoRetencaoEventos;
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
import org.springframework.kafka.core.KafkaAdmin;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

@AutoConfiguration
@EnableScheduling
@EnableConfigurationProperties({PropriedadesEventos.class, PropriedadesRetencaoEventos.class})
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
    RepositorioRetencaoEventos repositorioRetencaoEventos(JdbcClient banco) {
        return new RepositorioRetencaoEventos(banco);
    }

    @Bean
    ServicoRetencaoEventos servicoRetencaoEventos(
            RepositorioRetencaoEventos repositorio,
            PropriedadesRetencaoEventos propriedades,
            Clock relogio,
            MeterRegistry metricas) {
        return new ServicoRetencaoEventos(repositorio, propriedades, relogio, metricas);
    }

    @Bean
    MetricasEventos metricasEventos(
            RepositorioEventos repositorio,
            MeterRegistry registro) {
        return new MetricasEventos(repositorio, registro);
    }

    @Bean
    RoteadorTopicosEventos roteadorTopicosEventos(PropriedadesEventos propriedades) {
        return new RoteadorTopicosEventos(propriedades);
    }

    @Bean
    KafkaAdmin.NewTopics topicosEventos(PropriedadesEventos propriedades) {
        NewTopic[] topicos = propriedades.topicos().todos().stream()
                .flatMap(topico -> java.util.stream.Stream.of(topico, topico + ".dlt"))
                .map(topico -> TopicBuilder.name(topico)
                        .partitions(propriedades.particoes())
                        .build())
                .toArray(NewTopic[]::new);
        return new KafkaAdmin.NewTopics(topicos);
    }

    @Bean
    ServicoFilaEventos servicoFilaEventos(
            RepositorioEventos repositorio,
            PropriedadesEventos propriedades,
            Clock relogio) {
        return new ServicoFilaEventos(repositorio, propriedades, relogio);
    }

    @Bean
    ControladorQuarentena controladorQuarentena(
            ServicoQuarentena servico) {
        return new ControladorQuarentena(servico);
    }

    @Bean
    ServicoQuarentena servicoQuarentena(
            RepositorioEventos repositorio,
            Clock relogio) {
        return new ServicoQuarentena(repositorio, relogio);
    }

    @Bean
    PublicadorEventos publicadorEventos(
            ServicoFilaEventos fila,
            KafkaTemplate<String, EventoSaga> kafka,
            PropriedadesEventos propriedades,
            RoteadorTopicosEventos roteador,
            Clock relogio,
            MetricasEventos metricas) {
        return new PublicadorEventos(fila, kafka, propriedades, roteador, relogio, metricas);
    }
}
