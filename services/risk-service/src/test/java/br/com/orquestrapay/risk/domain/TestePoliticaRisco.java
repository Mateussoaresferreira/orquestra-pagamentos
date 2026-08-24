package br.com.orquestrapay.risk.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import java.time.Duration;

import org.junit.jupiter.api.Test;

class TestePoliticaRisco {

    @Test
    void deveNormalizarPaisEManterParametrosConfigurados() {
        var politica = criar(" br ", new BigDecimal("1000.00"), new BigDecimal("5000.00"));

        assertThat(politica.paisBase()).isEqualTo("BR");
        assertThat(politica.janelaComprasRecentes()).isEqualTo(Duration.ofMinutes(10));
        assertThat(politica.limiteReprovacao()).isEqualTo(70);
    }

    @Test
    void deveRecusarFaixasDeValorInvertidas() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> criar("BR", new BigDecimal("5000.00"), new BigDecimal("1000.00")))
                .withMessageContaining("muito alto");
    }

    @Test
    void deveRecusarPaisForaDoPadraoIso() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> criar("Brasil", new BigDecimal("1000.00"), new BigDecimal("5000.00")))
                .withMessageContaining("ISO 3166-1");
    }

    private PoliticaRisco criar(String pais, BigDecimal limiteAlto, BigDecimal limiteMuitoAlto) {
        return new PoliticaRisco(
                limiteAlto,
                limiteMuitoAlto,
                15,
                45,
                pais,
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
