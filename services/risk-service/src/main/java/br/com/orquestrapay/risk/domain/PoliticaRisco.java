package br.com.orquestrapay.risk.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

public record PoliticaRisco(
        BigDecimal limiteValorAlto,
        BigDecimal limiteValorMuitoAlto,
        int pontosValorAlto,
        int pontosValorMuitoAlto,
        String paisBase,
        int pontosPaisDivergente,
        int limiteComprasRecentes,
        Duration janelaComprasRecentes,
        int pontosAltaVelocidade,
        int limiteClientesPorDispositivo,
        Duration janelaDispositivoCompartilhado,
        int pontosDispositivoCompartilhado,
        int limiteReprovacao) {

    public PoliticaRisco {
        exigirPositivo(limiteValorAlto, "limiteValorAlto");
        exigirPositivo(limiteValorMuitoAlto, "limiteValorMuitoAlto");
        if (limiteValorMuitoAlto.compareTo(limiteValorAlto) <= 0) {
            throw new IllegalArgumentException("O limite de valor muito alto deve superar o limite de valor alto");
        }

        paisBase = Objects.requireNonNull(paisBase, "paisBase")
                .trim()
                .toUpperCase(Locale.ROOT);
        if (!paisBase.matches("[A-Z]{2}")) {
            throw new IllegalArgumentException("O pais base deve usar duas letras no formato ISO 3166-1 alpha-2");
        }

        exigirContagemPositiva(limiteComprasRecentes, "limiteComprasRecentes");
        exigirDuracaoPositiva(janelaComprasRecentes, "janelaComprasRecentes");
        exigirContagemPositiva(limiteClientesPorDispositivo, "limiteClientesPorDispositivo");
        exigirDuracaoPositiva(janelaDispositivoCompartilhado, "janelaDispositivoCompartilhado");

        exigirPontuacao(pontosValorAlto, "pontosValorAlto");
        exigirPontuacao(pontosValorMuitoAlto, "pontosValorMuitoAlto");
        exigirPontuacao(pontosPaisDivergente, "pontosPaisDivergente");
        exigirPontuacao(pontosAltaVelocidade, "pontosAltaVelocidade");
        exigirPontuacao(pontosDispositivoCompartilhado, "pontosDispositivoCompartilhado");
        if (limiteReprovacao < 1 || limiteReprovacao > 100) {
            throw new IllegalArgumentException("O limite de reprovacao deve estar entre 1 e 100");
        }
    }

    private static void exigirPositivo(BigDecimal valor, String campo) {
        if (Objects.requireNonNull(valor, campo).signum() <= 0) {
            throw new IllegalArgumentException(campo + " deve ser positivo");
        }
    }

    private static void exigirContagemPositiva(int valor, String campo) {
        if (valor < 1) {
            throw new IllegalArgumentException(campo + " deve ser maior que zero");
        }
    }

    private static void exigirDuracaoPositiva(Duration valor, String campo) {
        if (Objects.requireNonNull(valor, campo).isZero() || valor.isNegative()) {
            throw new IllegalArgumentException(campo + " deve ser positiva");
        }
    }

    private static void exigirPontuacao(int valor, String campo) {
        if (valor < 0 || valor > 100) {
            throw new IllegalArgumentException(campo + " deve estar entre 0 e 100");
        }
    }
}
