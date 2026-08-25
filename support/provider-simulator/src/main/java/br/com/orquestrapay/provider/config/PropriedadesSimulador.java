package br.com.orquestrapay.provider.config;

import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("provedor.simulacao")
public record PropriedadesSimulador(
        String nome,
        String segredoWebhook,
        Set<String> hostsNotificacaoPermitidos) {

    public PropriedadesSimulador {
        if (nome == null || !nome.matches("[a-z0-9-]{2,40}")) {
            throw new IllegalArgumentException("O nome do provedor e invalido");
        }
        if (segredoWebhook == null || segredoWebhook.length() < 24) {
            throw new IllegalArgumentException("O segredo de webhook deve ter ao menos 24 caracteres");
        }
        hostsNotificacaoPermitidos = hostsNotificacaoPermitidos == null
                ? Set.of("localhost", "servico-pagamento")
                : Set.copyOf(hostsNotificacaoPermitidos);
    }
}
