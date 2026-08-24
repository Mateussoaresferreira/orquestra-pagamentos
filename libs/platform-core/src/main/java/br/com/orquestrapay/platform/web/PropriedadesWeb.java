package br.com.orquestrapay.platform.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("orquestrapay.web")
public record PropriedadesWeb(long tamanhoMaximoCorpoBytes) {

    private static final long UM_MEBIBYTE = 1024L * 1024L;

    public PropriedadesWeb {
        if (tamanhoMaximoCorpoBytes == 0) {
            tamanhoMaximoCorpoBytes = UM_MEBIBYTE;
        }
        if (tamanhoMaximoCorpoBytes < 1024 || tamanhoMaximoCorpoBytes > 10 * UM_MEBIBYTE) {
            throw new IllegalArgumentException(
                    "O limite do corpo HTTP deve ficar entre 1 KiB e 10 MiB");
        }
    }
}
