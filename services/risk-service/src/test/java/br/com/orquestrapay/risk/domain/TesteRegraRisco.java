package br.com.orquestrapay.risk.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;

import org.junit.jupiter.api.Test;

class TesteRegraRisco {

    private static final PoliticaRisco POLITICA = politicaPadrao();

    @Test
    void deveAplicarPontuacaoDeValorNasFaixasCorretas() {
        var regra = new RegraRisco.RegraValor(POLITICA);

        assertThat(regra.avaliar(contexto("1000.00", "BR", 0, 0))).isEmpty();
        assertThat(regra.avaliar(contexto("1000.01", "BR", 0, 0)))
                .contains(new SinalRisco("VALOR_ALTO", 15, "Compra acima do limite alto de 1000.00"));
        assertThat(regra.avaliar(contexto("5000.01", "BR", 0, 0)))
                .contains(new SinalRisco(
                        "VALOR_MUITO_ALTO",
                        45,
                        "Compra acima do limite muito alto de 5000.00"));
    }

    @Test
    void deveSinalizarCompraOriginadaForaDoBrasil() {
        var regra = new RegraRisco.RegraPais(POLITICA);

        assertThat(regra.avaliar(contexto("100.00", "BR", 0, 0))).isEmpty();
        assertThat(regra.avaliar(contexto("100.00", "US", 0, 0)))
                .get()
                .extracting(SinalRisco::codigo, SinalRisco::pontos)
                .containsExactly("PAIS_DIVERGENTE", 25);
    }

    @Test
    void deveSinalizarVelocidadeSomenteAPartirDaTerceiraCompra() {
        var regra = new RegraRisco.RegraVelocidade(POLITICA);

        assertThat(regra.avaliar(contexto("100.00", "BR", 2, 0))).isEmpty();
        assertThat(regra.avaliar(contexto("100.00", "BR", 3, 0)))
                .get()
                .extracting(SinalRisco::codigo, SinalRisco::pontos)
                .containsExactly("ALTA_VELOCIDADE", 35);
    }

    @Test
    void deveSinalizarDispositivoCompartilhadoPorTresClientes() {
        var regra = new RegraRisco.RegraDispositivoCompartilhado(POLITICA);

        assertThat(regra.avaliar(contexto("100.00", "BR", 0, 2))).isEmpty();
        assertThat(regra.avaliar(contexto("100.00", "BR", 0, 3)))
                .get()
                .extracting(SinalRisco::codigo, SinalRisco::pontos)
                .containsExactly("DISPOSITIVO_COMPARTILHADO", 40);
    }

    private ContextoRisco contexto(String valor, String pais, int comprasRecentes, int clientesNoDispositivo) {
        return new ContextoRisco(new BigDecimal(valor), pais, comprasRecentes, clientesNoDispositivo);
    }

    private static PoliticaRisco politicaPadrao() {
        return new PoliticaRisco(
                new BigDecimal("1000.00"),
                new BigDecimal("5000.00"),
                15,
                45,
                "BR",
                25,
                3,
                Duration.ofMinutes(10),
                35,
                3,
                Duration.ofHours(24),
                40,
                70);
    }
}
