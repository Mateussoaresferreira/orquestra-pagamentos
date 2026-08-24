package br.com.orquestrapay.risk.config;

import java.math.BigDecimal;
import java.time.Duration;

import br.com.orquestrapay.risk.domain.PoliticaRisco;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("orquestrapay.risco.politica")
public record PropriedadesPoliticaRisco(
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

    PoliticaRisco paraDominio() {
        return new PoliticaRisco(
                limiteValorAlto,
                limiteValorMuitoAlto,
                pontosValorAlto,
                pontosValorMuitoAlto,
                paisBase,
                pontosPaisDivergente,
                limiteComprasRecentes,
                janelaComprasRecentes,
                pontosAltaVelocidade,
                limiteClientesPorDispositivo,
                janelaDispositivoCompartilhado,
                pontosDispositivoCompartilhado,
                limiteReprovacao);
    }
}
