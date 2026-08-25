package br.com.orquestrapay.payment.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import br.com.orquestrapay.contracts.MetodoPagamento;
import br.com.orquestrapay.payment.config.PropriedadesPagamentos;
import br.com.orquestrapay.payment.config.PropriedadesProvedor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class TesteIntegracaoLimitadorChamadasProvedor {

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:8.2-alpine"))
            .withExposedPorts(6379);

    private static LettuceConnectionFactory conexao;
    private static StringRedisTemplate redis;

    @BeforeAll
    static void conectar() {
        conexao = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        conexao.afterPropertiesSet();
        redis = new StringRedisTemplate(conexao);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void desconectar() {
        conexao.destroy();
    }

    @BeforeEach
    void limpar() {
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @Test
    void deveCompartilharACotaEntreDuasReplicas() {
        PropriedadesProvedor provedor = provedor(2, Duration.ofSeconds(10));
        PropriedadesPagamentos propriedades = propriedades(provedor);
        var primeiraReplica = new LimitadorChamadasProvedor(
                redis,
                propriedades,
                new SimpleMeterRegistry());
        var segundaReplica = new LimitadorChamadasProvedor(
                redis,
                propriedades,
                new SimpleMeterRegistry());

        assertThat(primeiraReplica.consumir("principal", provedor).permitido()).isTrue();
        assertThat(segundaReplica.consumir("principal", provedor).permitido()).isTrue();
        var excedente = primeiraReplica.consumir("principal", provedor);

        assertThat(excedente.permitido()).isFalse();
        assertThat(excedente.restante()).isZero();
        assertThat(excedente.tentarNovamenteEmMillis()).isPositive();
    }

    @Test
    void deveReporACotaSemRajadaNaViradaDeJanela() throws InterruptedException {
        PropriedadesProvedor provedor = provedor(1, Duration.ofMillis(200));
        var limitador = new LimitadorChamadasProvedor(
                redis,
                propriedades(provedor),
                new SimpleMeterRegistry());

        assertThat(limitador.consumir("principal", provedor).permitido()).isTrue();
        assertThat(limitador.consumir("principal", provedor).permitido()).isFalse();
        Thread.sleep(250);
        assertThat(limitador.consumir("principal", provedor).permitido()).isTrue();
    }

    private PropriedadesPagamentos propriedades(PropriedadesProvedor provedor) {
        return new PropriedadesPagamentos(
                Map.of("principal", provedor),
                null,
                null,
                new PropriedadesPagamentos.ControleProvedores(true, false));
    }

    private PropriedadesProvedor provedor(int maximoChamadas, Duration periodo) {
        return new PropriedadesProvedor(
                URI.create("http://localhost:8090"),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                "chave-api-provedor-para-testes",
                "segredo-webhook-provedor-teste",
                10,
                Set.of(MetodoPagamento.CARTAO),
                2,
                maximoChamadas,
                periodo);
    }
}
