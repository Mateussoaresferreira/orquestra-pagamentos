package br.com.orquestrapay.notification.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

class TesteConfiguracaoCriptografiaNotificacao {

    @Test
    void deveExigirChaveDeCriptografiaNoAmbiente() throws IOException {
        var propriedades = new YamlPropertiesFactoryBean();
        propriedades.setResources(new ClassPathResource("application.yml"));

        var configuracao = propriedades.getObject();

        assertThat(configuracao)
                .isNotNull()
                .containsEntry(
                        "orquestrapay.criptografia.chave-token-base64",
                        "${CHAVE_CRIPTOGRAFIA_TOKEN}");
    }
}
