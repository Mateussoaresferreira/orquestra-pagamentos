package br.com.orquestrapay.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class TesteProtecaoTokenPagamento {

    private static final String CHAVE_VALIDA = Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    private final ProtecaoTokenPagamento protecao = new ProtecaoTokenPagamento(
            new PropriedadesCriptografia(CHAVE_VALIDA));

    @Test
    void deveProtegerERevelarTokenSemArmazenarTextoOriginal() {
        UUID idCompra = UUID.randomUUID();

        String tokenProtegido = protecao.proteger("tok_cliente_123", idCompra);

        assertThat(tokenProtegido)
                .startsWith("v1:")
                .doesNotContain("tok_cliente_123");
        assertThat(protecao.revelar(tokenProtegido, idCompra)).isEqualTo("tok_cliente_123");
    }

    @Test
    void deveGerarCifrasDiferentesParaOMesmoToken() {
        UUID idCompra = UUID.randomUUID();

        String primeiraCifra = protecao.proteger("tok_repetido", idCompra);
        String segundaCifra = protecao.proteger("tok_repetido", idCompra);

        assertThat(primeiraCifra).isNotEqualTo(segundaCifra);
        assertThat(protecao.revelar(primeiraCifra, idCompra)).isEqualTo("tok_repetido");
        assertThat(protecao.revelar(segundaCifra, idCompra)).isEqualTo("tok_repetido");
    }

    @Test
    void deveRecusarTokenQuandoPertenceAOutraCompra() {
        String tokenProtegido = protecao.proteger("tok_cliente_123", UUID.randomUUID());

        assertThatThrownBy(() -> protecao.revelar(tokenProtegido, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Nao foi possivel revelar o token de pagamento");
    }

    @Test
    void deveRecusarTokenSemCriptografia() {
        assertThatThrownBy(() -> protecao.revelar("tok_sem_criptografia", UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Nao foi possivel revelar o token de pagamento");
    }

    @Test
    void deveGerarImpressaoDeterministicaSemExporOValor() {
        String primeira = protecao.calcularImpressao("pagamento", "tok_cliente_123");
        String segunda = protecao.calcularImpressao("pagamento", "tok_cliente_123");
        String outroContexto = protecao.calcularImpressao("idempotencia", "tok_cliente_123");

        assertThat(primeira)
                .hasSize(64)
                .isEqualTo(segunda)
                .isNotEqualTo(outroContexto)
                .doesNotContain("tok_cliente_123");
    }

    @Test
    void deveRecusarChaveComTamanhoInvalido() {
        String chaveCurta = Base64.getEncoder().encodeToString(
                "chave-curta".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> new ProtecaoTokenPagamento(new PropriedadesCriptografia(chaveCurta)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A chave de criptografia deve possuir 256 bits");
    }

    @Test
    void deveRevelarTokenAntigoDepoisDaRotacaoDaChave() {
        UUID idCompra = UUID.randomUUID();
        String tokenAntigo = protecao.proteger("tok_em_transito", idCompra);
        String novaChave = Base64.getEncoder().encodeToString(
                "abcdef0123456789abcdef0123456789".getBytes(StandardCharsets.UTF_8));
        var protecaoRotacionada = new ProtecaoTokenPagamento(new PropriedadesCriptografia(
                null,
                "v2",
                Map.of("v1", CHAVE_VALIDA, "v2", novaChave),
                CHAVE_VALIDA));

        String tokenNovo = protecaoRotacionada.proteger("tok_novo", idCompra);

        assertThat(tokenNovo).startsWith("v2:v2:");
        assertThat(protecaoRotacionada.revelar(tokenAntigo, idCompra)).isEqualTo("tok_em_transito");
        assertThat(protecaoRotacionada.revelar(tokenNovo, idCompra)).isEqualTo("tok_novo");
    }
}
