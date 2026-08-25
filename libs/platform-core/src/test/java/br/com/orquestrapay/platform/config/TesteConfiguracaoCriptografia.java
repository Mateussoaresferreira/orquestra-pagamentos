package br.com.orquestrapay.platform.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import br.com.orquestrapay.platform.security.PropriedadesCriptografia;
import br.com.orquestrapay.platform.security.ProtecaoTokenPagamento;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class TesteConfiguracaoCriptografia {

    private final ApplicationContextRunner contexto = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfiguracaoCriptografia.class));

    @Test
    void deveCriarProtecaoComChaveRecebidaDoAmbiente() {
        var chave = Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

        contexto.withPropertyValues(
                        "orquestrapay.criptografia.chave-token-base64=" + chave)
                .run(aplicacao -> {
                    assertThat(aplicacao).hasSingleBean(PropriedadesCriptografia.class);
                    assertThat(aplicacao).hasSingleBean(ProtecaoTokenPagamento.class);
                    assertThat(aplicacao.getBean(PropriedadesCriptografia.class).chaves())
                            .containsEntry("v1", chave);
                });
    }
}
