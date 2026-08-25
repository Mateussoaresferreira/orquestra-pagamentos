package br.com.orquestrapay.ledger.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public final class CalculadoraParcelas {

    private CalculadoraParcelas() {
    }

    public static List<ParcelaPlanejada> calcular(
            BigDecimal valorTotal,
            int totalParcelas,
            LocalDate dataReferencia) {
        if (valorTotal == null || valorTotal.signum() <= 0) {
            throw new IllegalArgumentException("O valor total deve ser positivo");
        }
        if (totalParcelas < 1 || totalParcelas > 12) {
            throw new IllegalArgumentException("A quantidade de parcelas deve ficar entre 1 e 12");
        }

        long centavos = valorTotal
                .setScale(2, RoundingMode.UNNECESSARY)
                .movePointRight(2)
                .longValueExact();
        if (centavos < totalParcelas) {
            throw new IllegalArgumentException("Cada parcela deve possuir ao menos um centavo");
        }

        long valorBase = centavos / totalParcelas;
        long centavosRestantes = centavos % totalParcelas;
        var parcelas = new ArrayList<ParcelaPlanejada>(totalParcelas);
        for (int numero = 1; numero <= totalParcelas; numero++) {
            long valorParcela = valorBase + (numero <= centavosRestantes ? 1 : 0);
            parcelas.add(new ParcelaPlanejada(
                    numero,
                    totalParcelas,
                    BigDecimal.valueOf(valorParcela, 2),
                    dataReferencia.plusMonths(numero)));
        }
        return List.copyOf(parcelas);
    }

    public record ParcelaPlanejada(
            int numero,
            int totalParcelas,
            BigDecimal valor,
            LocalDate vencimento) {
    }
}
