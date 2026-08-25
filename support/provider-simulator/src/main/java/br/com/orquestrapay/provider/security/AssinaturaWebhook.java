package br.com.orquestrapay.provider.security;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class AssinaturaWebhook {

    private AssinaturaWebhook() {
    }

    public static String assinar(String segredo, String conteudo) {
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(
                    segredo.getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"));
            return HexFormat.of().formatHex(
                    hmac.doFinal(conteudo.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException excecao) {
            throw new IllegalStateException("Falha ao assinar webhook", excecao);
        }
    }
}
