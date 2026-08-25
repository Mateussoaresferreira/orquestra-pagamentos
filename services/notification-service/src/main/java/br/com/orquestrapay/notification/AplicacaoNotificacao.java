package br.com.orquestrapay.notification;

import br.com.orquestrapay.notification.config.PropriedadesNotificacoes;
import br.com.orquestrapay.notification.config.PropriedadesWebhooks;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({PropriedadesNotificacoes.class, PropriedadesWebhooks.class})
public class AplicacaoNotificacao {

    public static void main(String[] argumentos) {
        SpringApplication.run(AplicacaoNotificacao.class, argumentos);
    }
}
