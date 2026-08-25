package br.com.orquestrapay.provider.service;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.EnumMap;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.springframework.stereotype.Component;

@Component
public class GeradorBrCodePix {

    private static final String CHAVE_PIX_SIMULADA = "pix@orquestrapay.local";
    private static final String NOME_RECEBEDOR = "ORQUESTRA PAY";
    private static final String CIDADE_RECEBEDOR = "SAO PAULO";
    private static final int TAMANHO_QR_CODE = 320;

    public CobrancaPix gerar(BigDecimal valor, String txid) {
        validar(valor, txid);

        String contaRecebedor = campo("00", "BR.GOV.BCB.PIX")
                + campo("01", CHAVE_PIX_SIMULADA);
        String dadosAdicionais = campo("05", txid);
        String semCrc = campo("00", "01")
                + campo("01", "12")
                + campo("26", contaRecebedor)
                + campo("52", "0000")
                + campo("53", "986")
                + campo("54", valor.setScale(2, RoundingMode.HALF_UP).toPlainString())
                + campo("58", "BR")
                + campo("59", NOME_RECEBEDOR)
                + campo("60", CIDADE_RECEBEDOR)
                + campo("62", dadosAdicionais)
                + "6304";
        String copiaCola = semCrc + calcularCrc16Ccitt(semCrc);
        return new CobrancaPix(copiaCola, gerarImagem(copiaCola));
    }

    private String gerarImagem(String conteudo) {
        try {
            var opcoes = new EnumMap<EncodeHintType, Object>(EncodeHintType.class);
            opcoes.put(EncodeHintType.CHARACTER_SET, StandardCharsets.UTF_8.name());
            opcoes.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            opcoes.put(EncodeHintType.MARGIN, 2);
            var matriz = new QRCodeWriter().encode(
                    conteudo,
                    BarcodeFormat.QR_CODE,
                    TAMANHO_QR_CODE,
                    TAMANHO_QR_CODE,
                    opcoes);
            var saida = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matriz, "PNG", saida);
            return Base64.getEncoder().encodeToString(saida.toByteArray());
        } catch (WriterException | java.io.IOException excecao) {
            throw new IllegalStateException("Nao foi possivel gerar o QR Code PIX", excecao);
        }
    }

    private String campo(String id, String valor) {
        int tamanho = valor.getBytes(StandardCharsets.UTF_8).length;
        if (tamanho > 99) {
            throw new IllegalArgumentException("Campo BR Code excede 99 bytes: " + id);
        }
        return id + "%02d".formatted(tamanho) + valor;
    }

    private String calcularCrc16Ccitt(String conteudo) {
        int crc = 0xFFFF;
        for (byte valor : conteudo.getBytes(StandardCharsets.UTF_8)) {
            crc ^= (valor & 0xFF) << 8;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x8000) != 0
                        ? ((crc << 1) ^ 0x1021) & 0xFFFF
                        : (crc << 1) & 0xFFFF;
            }
        }
        return "%04X".formatted(crc);
    }

    private void validar(BigDecimal valor, String txid) {
        if (valor == null || valor.signum() <= 0) {
            throw new IllegalArgumentException("O valor do BR Code deve ser positivo");
        }
        if (txid == null || !txid.matches("[A-Za-z0-9]{1,25}")) {
            throw new IllegalArgumentException("O txid deve possuir de 1 a 25 caracteres alfanumericos");
        }
    }

    public record CobrancaPix(String copiaCola, String imagemQrCodeBase64) {
    }
}
