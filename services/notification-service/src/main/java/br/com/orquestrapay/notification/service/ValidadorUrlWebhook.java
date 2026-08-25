package br.com.orquestrapay.notification.service;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

import br.com.orquestrapay.notification.config.PropriedadesWebhooks;
import br.com.orquestrapay.platform.web.ExcecaoNegocio;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class ValidadorUrlWebhook {

    private final PropriedadesWebhooks propriedades;

    public ValidadorUrlWebhook(PropriedadesWebhooks propriedades) {
        this.propriedades = propriedades;
    }

    public URI validar(String valor) {
        URI url;
        try {
            url = URI.create(valor).normalize();
        } catch (IllegalArgumentException excecao) {
            throw problema("url-webhook-invalida", "A URL do webhook e invalida");
        }
        String esquema = url.getScheme();
        boolean httpLocal = propriedades.permitirEnderecosPrivados()
                && "http".equalsIgnoreCase(esquema);
        if (!("https".equalsIgnoreCase(esquema) || httpLocal)
                || url.getHost() == null
                || url.getUserInfo() != null
                || url.getFragment() != null
                || url.getQuery() != null) {
            throw problema(
                    "url-webhook-nao-permitida",
                    "Use HTTPS, host explicito e URL sem credenciais, query ou fragmento");
        }

        if (!propriedades.permitirEnderecosPrivados()) {
            validarEnderecosPublicos(url.getHost());
        }
        return url;
    }

    private void validarEnderecosPublicos(String host) {
        try {
            for (InetAddress endereco : InetAddress.getAllByName(host)) {
                if (endereco.isAnyLocalAddress()
                        || endereco.isLoopbackAddress()
                        || endereco.isLinkLocalAddress()
                        || endereco.isSiteLocalAddress()
                        || endereco.isMulticastAddress()) {
                    throw problema(
                            "destino-webhook-privado",
                            "O webhook deve apontar para um endereco publico");
                }
            }
        } catch (UnknownHostException excecao) {
            throw problema(
                    "host-webhook-indisponivel",
                    "O host do webhook nao pode ser resolvido");
        }
    }

    private ExcecaoNegocio problema(String codigo, String detalhe) {
        return new ExcecaoNegocio(HttpStatus.BAD_REQUEST, codigo, detalhe);
    }
}
