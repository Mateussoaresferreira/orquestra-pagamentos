package br.com.orquestrapay.sdk.config;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.orquestrapay.sdk.client.ClienteOrquestraPay;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class TesteConfiguracaoClienteOrquestra {

    private final ApplicationContextRunner contexto = new ApplicationContextRunner()
            .withUserConfiguration(ConfiguracaoClienteOrquestra.class);

    @Test
    void deveCriarClienteQuandoEmpresaForConfigurada() {
        contexto.withPropertyValues(
                        "orquestrapay.cliente.id-empresa=8f63e344-b927-4df3-9a9c-d27451d26e98",
                        "orquestrapay.cliente.tempo-limite=2s")
                .run(resultado -> {
                    assertThat(resultado).hasNotFailed();
                    assertThat(resultado).hasSingleBean(ClienteOrquestraPay.class);
                    assertThat(resultado.getBean(PropriedadesClienteOrquestra.class).tempoLimite())
                            .isEqualTo(java.time.Duration.ofSeconds(2));
                });
    }

    @Test
    void naoDeveCriarClienteSemIdentificarEmpresa() {
        contexto.run(resultado -> {
            assertThat(resultado).hasNotFailed();
            assertThat(resultado).doesNotHaveBean(ClienteOrquestraPay.class);
        });
    }
}
