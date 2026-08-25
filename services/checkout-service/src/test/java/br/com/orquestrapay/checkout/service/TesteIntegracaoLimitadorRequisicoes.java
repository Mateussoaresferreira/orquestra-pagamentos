package br.com.orquestrapay.checkout.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;

import br.com.orquestrapay.checkout.config.PropriedadesLimiteRequisicoes;
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
class TesteIntegracaoLimitadorRequisicoes {

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
    void deveAplicarLimitesDaEmpresaEGlobalSemConsumirTokenAoRecusar() {
        var limitador = new LimitadorRequisicoes(
                redis,
                new PropriedadesLimiteRequisicoes(
                        true,
                        2,
                        Duration.ofSeconds(10),
                        3,
                        Duration.ofSeconds(10),
                        false,
                        32));
        String empresaA = UUID.randomUUID().toString();
        String empresaB = UUID.randomUUID().toString();

        assertThat(limitador.consumir(empresaA).permitido()).isTrue();
        assertThat(limitador.consumir(empresaA).permitido()).isTrue();
        var excessoEmpresa = limitador.consumir(empresaA);
        assertThat(excessoEmpresa.permitido()).isFalse();
        assertThat(excessoEmpresa.restanteGlobal()).isEqualTo(1);

        assertThat(limitador.consumir(empresaB).permitido()).isTrue();
        var excessoGlobal = limitador.consumir(empresaB);
        assertThat(excessoGlobal.permitido()).isFalse();
        assertThat(excessoGlobal.restanteGlobal()).isZero();
        assertThat(excessoGlobal.tentarNovamenteEmMillis()).isPositive();
    }

    @Test
    void deveReporTokensProgressivamente() throws InterruptedException {
        var limitador = new LimitadorRequisicoes(
                redis,
                new PropriedadesLimiteRequisicoes(
                        true,
                        1,
                        Duration.ofMillis(200),
                        10,
                        Duration.ofMillis(200),
                        false,
                        32));
        String empresa = UUID.randomUUID().toString();

        assertThat(limitador.consumir(empresa).permitido()).isTrue();
        assertThat(limitador.consumir(empresa).permitido()).isFalse();
        Thread.sleep(250);
        assertThat(limitador.consumir(empresa).permitido()).isTrue();
    }
}
