package br.com.orquestrapay.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.imageio.ImageIO;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import org.junit.jupiter.api.Test;

class TesteGeradorBrCodePix {

    private final GeradorBrCodePix gerador = new GeradorBrCodePix("pix@orquestrapay.local");

    @Test
    void deveGerarBrCodeComCamposEmvCrcValidoEImagemDecodificavel() throws Exception {
        String txid = "pix0123456789012345678901";

        var cobranca = gerador.gerar(new BigDecimal("149.90"), txid);

        Map<String, String> campos = separarCampos(cobranca.copiaCola());
        assertThat(campos)
                .containsEntry("00", "01")
                .containsEntry("01", "12")
                .containsEntry("52", "0000")
                .containsEntry("53", "986")
                .containsEntry("54", "149.90")
                .containsEntry("58", "BR")
                .containsEntry("59", "ORQUESTRA PAY")
                .containsEntry("60", "SAO PAULO");
        assertThat(campos.get("26"))
                .contains("0014BR.GOV.BCB.PIX")
                .contains("pix@orquestrapay.local");
        assertThat(campos.get("62")).contains(txid);
        assertThat(campos.get("63")).isEqualTo(calcularCrc(cobranca.copiaCola()));

        byte[] imagem = Base64.getDecoder().decode(cobranca.imagemQrCodeBase64());
        assertThat(imagem).startsWith((byte) 0x89, (byte) 0x50, (byte) 0x4E, (byte) 0x47);
        var imagemQr = ImageIO.read(new ByteArrayInputStream(imagem));
        var bitmap = new BinaryBitmap(new HybridBinarizer(
                new BufferedImageLuminanceSource(imagemQr)));

        assertThat(new MultiFormatReader().decode(bitmap).getText())
                .isEqualTo(cobranca.copiaCola());
    }

    @Test
    void deveRejeitarTxidQueNaoCabeNoCampoDoBrCode() {
        assertThatThrownBy(() -> gerador.gerar(
                new BigDecimal("10.00"),
                "txid-com-mais-de-vinte-e-cinco-caracteres"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1 a 25");
    }

    @Test
    void deveUsarChavePixConfiguradaSemFixaLaNoCodigo() {
        var geradorComCpfSimulado = new GeradorBrCodePix("12345678909");

        var cobranca = geradorComCpfSimulado.gerar(
                new BigDecimal("25.00"),
                "pedido123");

        assertThat(separarCampos(cobranca.copiaCola()).get("26"))
                .contains("0014BR.GOV.BCB.PIX")
                .contains("011112345678909");
    }

    @Test
    void deveRejeitarChavePixComCaracteresInvalidos() {
        assertThatThrownBy(() -> new GeradorBrCodePix("cpf com espacos"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chave PIX");
    }

    private Map<String, String> separarCampos(String payload) {
        Map<String, String> campos = new LinkedHashMap<>();
        int posicao = 0;
        while (posicao < payload.length()) {
            String id = payload.substring(posicao, posicao + 2);
            int tamanho = Integer.parseInt(payload.substring(posicao + 2, posicao + 4));
            int inicio = posicao + 4;
            int fim = inicio + tamanho;
            campos.put(id, payload.substring(inicio, fim));
            posicao = fim;
        }
        return campos;
    }

    private String calcularCrc(String payloadCompleto) {
        String semCrc = payloadCompleto.substring(0, payloadCompleto.length() - 4);
        int crc = 0xFFFF;
        for (byte valor : semCrc.getBytes(StandardCharsets.UTF_8)) {
            crc ^= (valor & 0xFF) << 8;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x8000) != 0
                        ? ((crc << 1) ^ 0x1021) & 0xFFFF
                        : (crc << 1) & 0xFFFF;
            }
        }
        return "%04X".formatted(crc);
    }
}
