package br.com.orquestrapay.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class TesteCalculadoraParcelas {

    @Test
    void deveDistribuirCentavosSemAlterarOValorTotal() {
        var parcelas = CalculadoraParcelas.calcular(
                new BigDecimal("100.00"),
                3,
                LocalDate.of(2026, 8, 24));

        assertThat(parcelas).extracting(CalculadoraParcelas.ParcelaPlanejada::valor)
                .containsExactly(
                        new BigDecimal("33.34"),
                        new BigDecimal("33.33"),
                        new BigDecimal("33.33"));
        assertThat(parcelas).extracting(CalculadoraParcelas.ParcelaPlanejada::vencimento)
                .containsExactly(
                        LocalDate.of(2026, 9, 24),
                        LocalDate.of(2026, 10, 24),
                        LocalDate.of(2026, 11, 24));
        assertThat(parcelas.stream()
                .map(CalculadoraParcelas.ParcelaPlanejada::valor)
                .reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("100.00");
    }

    @Test
    void deveRecusarMaisParcelasDoQueCentavosDisponiveis() {
        assertThatThrownBy(() -> CalculadoraParcelas.calcular(
                new BigDecimal("0.02"),
                3,
                LocalDate.of(2026, 8, 24)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("centavo");
    }
}
