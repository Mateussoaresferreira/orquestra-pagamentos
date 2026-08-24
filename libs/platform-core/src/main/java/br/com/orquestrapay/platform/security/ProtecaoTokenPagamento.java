package br.com.orquestrapay.platform.security;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class ProtecaoTokenPagamento {

    private static final String PREFIXO = "v1:";
    private static final int TAMANHO_VETOR_INICIALIZACAO = 12;
    private static final int TAMANHO_TAG_AUTENTICACAO = 128;

    private final SecretKeySpec chave;
    private final SecretKeySpec chaveImpressao;
    private final SecureRandom aleatorio = new SecureRandom();

    public ProtecaoTokenPagamento(PropriedadesCriptografia propriedades) {
        byte[] bytesChave = Base64.getDecoder().decode(propriedades.chaveTokenBase64());
        if (bytesChave.length != 32) {
            throw new IllegalArgumentException("A chave de criptografia deve possuir 256 bits");
        }
        this.chave = new SecretKeySpec(bytesChave, "AES");
        this.chaveImpressao = derivarChaveImpressao(bytesChave);
    }

    public String proteger(String token, UUID idCompra) {
        try {
            byte[] vetorInicializacao = new byte[TAMANHO_VETOR_INICIALIZACAO];
            aleatorio.nextBytes(vetorInicializacao);
            Cipher cifra = Cipher.getInstance("AES/GCM/NoPadding");
            cifra.init(Cipher.ENCRYPT_MODE, chave,
                    new GCMParameterSpec(TAMANHO_TAG_AUTENTICACAO, vetorInicializacao));
            cifra.updateAAD(idCompra.toString().getBytes(UTF_8));
            byte[] textoCifrado = cifra.doFinal(token.getBytes(UTF_8));
            byte[] pacote = new byte[vetorInicializacao.length + textoCifrado.length];
            System.arraycopy(vetorInicializacao, 0, pacote, 0, vetorInicializacao.length);
            System.arraycopy(textoCifrado, 0, pacote, vetorInicializacao.length, textoCifrado.length);
            return PREFIXO + Base64.getUrlEncoder().withoutPadding().encodeToString(pacote);
        } catch (GeneralSecurityException excecao) {
            throw new IllegalStateException("Nao foi possivel proteger o token de pagamento", excecao);
        }
    }

    public String revelar(String tokenProtegido, UUID idCompra) {
        if (tokenProtegido == null || !tokenProtegido.startsWith(PREFIXO)) {
            throw new IllegalStateException("Nao foi possivel revelar o token de pagamento");
        }
        try {
            byte[] pacote = Base64.getUrlDecoder().decode(tokenProtegido.substring(PREFIXO.length()));
            if (pacote.length <= TAMANHO_VETOR_INICIALIZACAO) {
                throw new IllegalArgumentException("Token protegido invalido");
            }
            byte[] vetorInicializacao = new byte[TAMANHO_VETOR_INICIALIZACAO];
            byte[] textoCifrado = new byte[pacote.length - TAMANHO_VETOR_INICIALIZACAO];
            System.arraycopy(pacote, 0, vetorInicializacao, 0, vetorInicializacao.length);
            System.arraycopy(pacote, vetorInicializacao.length, textoCifrado, 0, textoCifrado.length);
            Cipher cifra = Cipher.getInstance("AES/GCM/NoPadding");
            cifra.init(Cipher.DECRYPT_MODE, chave,
                    new GCMParameterSpec(TAMANHO_TAG_AUTENTICACAO, vetorInicializacao));
            cifra.updateAAD(idCompra.toString().getBytes(UTF_8));
            return new String(cifra.doFinal(textoCifrado), UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException excecao) {
            throw new IllegalStateException("Nao foi possivel revelar o token de pagamento", excecao);
        }
    }

    public String calcularImpressao(String contexto, String valor) {
        if (valor == null) {
            throw new IllegalArgumentException("O valor da impressao e obrigatorio");
        }
        return calcularImpressao(contexto, valor.getBytes(UTF_8));
    }

    public String calcularImpressao(String contexto, byte[] valor) {
        if (contexto == null || contexto.isBlank() || valor == null) {
            throw new IllegalArgumentException("Contexto e valor da impressao sao obrigatorios");
        }
        try {
            Mac autenticador = Mac.getInstance("HmacSHA256");
            autenticador.init(chaveImpressao);
            autenticador.update(contexto.getBytes(UTF_8));
            autenticador.update((byte) 0);
            return HexFormat.of().formatHex(autenticador.doFinal(valor));
        } catch (GeneralSecurityException excecao) {
            throw new IllegalStateException("Nao foi possivel calcular a impressao segura", excecao);
        }
    }

    private SecretKeySpec derivarChaveImpressao(byte[] bytesChave) {
        try {
            Mac derivador = Mac.getInstance("HmacSHA256");
            derivador.init(new SecretKeySpec(bytesChave, "HmacSHA256"));
            byte[] chaveDerivada = derivador.doFinal(
                    "orquestra-de-pagamentos:impressao:v1".getBytes(UTF_8));
            return new SecretKeySpec(chaveDerivada, "HmacSHA256");
        } catch (GeneralSecurityException excecao) {
            throw new IllegalStateException("Nao foi possivel derivar a chave de impressao", excecao);
        }
    }
}
