package br.com.orquestrapay.platform.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import br.com.orquestrapay.contracts.EventoSaga;
import br.com.orquestrapay.contracts.TiposEventos;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

class TestePublicadorEventos {

    @Test
    @SuppressWarnings("unchecked")
    void publicaComprasEmParaleloSemInverterEventosDaMesmaCompra() {
        var fila = mock(ServicoFilaEventos.class);
        KafkaTemplate<String, EventoSaga> kafka = mock(KafkaTemplate.class);
        var metricas = mock(MetricasEventos.class);
        var propriedades = new PropriedadesEventos(
                true,
                PropriedadesEventos.Topicos.padrao(),
                12,
                50,
                4,
                Duration.ofSeconds(2),
                Duration.ofSeconds(30),
                3,
                Duration.ofSeconds(1),
                Duration.ofMinutes(1));
        var registros = new CopyOnWriteArrayList<ProducerRecord<String, EventoSaga>>();
        UUID primeiraCompra = UUID.randomUUID();
        UUID segundaCompra = UUID.randomUUID();
        var segundo = evento(primeiraCompra, 2, TiposEventos.ANALISAR_RISCO);
        var independente = evento(segundaCompra, 3, TiposEventos.AUTORIZAR_PAGAMENTO);
        var primeiro = evento(primeiraCompra, 1, TiposEventos.RESERVAR_ESTOQUE);

        when(fila.reivindicar()).thenReturn(List.of(segundo, independente, primeiro));
        when(fila.confirmar(any())).thenReturn(3);
        when(kafka.send(any(ProducerRecord.class))).thenAnswer(invocacao -> {
            registros.add(invocacao.getArgument(0));
            return CompletableFuture.completedFuture(null);
        });

        var publicador = new PublicadorEventos(
                fila,
                kafka,
                propriedades,
                new RoteadorTopicosEventos(propriedades),
                Clock.fixed(Instant.parse("2026-08-24T18:00:00Z"), ZoneOffset.UTC),
                metricas);

        publicador.publicarPendentes();

        var tiposPrimeiraCompra = registros.stream()
                .filter(registro -> registro.key().equals(primeiraCompra.toString()))
                .map(registro -> registro.value().getTipo())
                .toList();
        assertThat(tiposPrimeiraCompra)
                .containsExactly(TiposEventos.RESERVAR_ESTOQUE, TiposEventos.ANALISAR_RISCO);
        assertThat(registros).hasSize(3);
        assertThat(registros).extracting(ProducerRecord::topic)
                .containsExactlyInAnyOrder(
                        propriedades.topicos().estoque(),
                        propriedades.topicos().risco(),
                        propriedades.topicos().pagamento());
        org.mockito.Mockito.verify(fila).confirmar(org.mockito.ArgumentMatchers.argThat(
                eventos -> eventos.size() == 3
                        && eventos.contains(primeiro)
                        && eventos.contains(segundo)
                        && eventos.contains(independente)));
    }

    private EventoPendente evento(UUID idCompra, long ordem, String tipo) {
        return new EventoPendente(
                UUID.randomUUID(),
                ordem,
                tipo,
                1,
                UUID.randomUUID(),
                UUID.randomUUID(),
                idCompra,
                "teste",
                "{}",
                "",
                Instant.parse("2026-08-24T18:00:00Z"),
                0,
                UUID.randomUUID());
    }
}
