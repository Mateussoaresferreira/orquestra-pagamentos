package br.com.orquestrapay.payment.config;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("orquestrapay.provedor")
public record PropriedadesProvedor(
        URI url,
        Duration tempoLimiteConexao,
        Duration tempoLimiteLeitura,
        String chaveApi) {

    public PropriedadesProvedor {
        Objects.requireNonNull(url, "A URL do provedor e obrigatoria");
        validarDuracaoPositiva(tempoLimiteConexao, "tempoLimiteConexao");
        validarDuracaoPositiva(tempoLimiteLeitura, "tempoLimiteLeitura");
        if (chaveApi == null || chaveApi.length() < 24) {
            throw new IllegalArgumentException("A chave de API do provedor deve ter ao menos 24 caracteres");
        }
    }

    private static void validarDuracaoPositiva(Duration duracao, String propriedade) {
        if (duracao == null || duracao.isZero() || duracao.isNegative()) {
            throw new IllegalArgumentException(propriedade + " deve ser maior que zero");
        }
    }
}
