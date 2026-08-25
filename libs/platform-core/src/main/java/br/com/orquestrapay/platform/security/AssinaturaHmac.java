package br.com.orquestrapay.platform.security;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class AssinaturaHmac {

    private static final String ALGORITMO = "HmacSHA256";

    private AssinaturaHmac() {
    }

    public static String assinar(String segredo, String conteudo) {
        try {
            Mac hmac = Mac.getInstance(ALGORITMO);
            hmac.init(new SecretKeySpec(segredo.getBytes(StandardCharsets.UTF_8), ALGORITMO));
            return HexFormat.of().formatHex(hmac.doFinal(conteudo.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException excecao) {
            throw new IllegalStateException("Nao foi possivel calcular a assinatura HMAC", excecao);
        }
    }

    public static boolean corresponde(String esperada, String recebida) {
        if (esperada == null || recebida == null) {
            return false;
        }
        return MessageDigest.isEqual(
                esperada.getBytes(StandardCharsets.US_ASCII),
                recebida.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }
}
