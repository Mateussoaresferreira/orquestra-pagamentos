package br.com.orquestrapay.risk.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import java.time.Duration;

import br.com.orquestrapay.risk.domain.ContextoRisco;
import org.junit.jupiter.api.Test;

class TesteConfiguracaoModelosRisco {

    @Test
    void deveMontarChampionEChallengerVersionados() {
        var configuracao = new ConfiguracaoPoliticaRisco();
        var politicaChampion = configuracao.politicaRisco(politica(70));
        var propriedades = new PropriedadesModelosRisco(
                "regras-transacionais",
                "1.0.0",
                true,
                "regras-transacionais",
                "1.1.0",
                25,
                politica(65));

        var experimento = configuracao.experimentoModelosRisco(
                politicaChampion,
                propriedades);

        var contexto = new ContextoRisco(new BigDecimal("100.00"), "BR", 0, 0);
        assertThat(experimento.avaliarChampion(contexto).versao()).isEqualTo("1.0.0");
        assertThat(experimento.avaliarChallenger(contexto))
                .get()
                .extracting(resultado -> resultado.versao())
                .isEqualTo("1.1.0");
        assertThat(experimento.percentualAmostragem()).isEqualTo(25);
    }

    @Test
    void deveDesabilitarAmostragemQuandoChallengerEstaDesabilitado() {
        var configuracao = new ConfiguracaoPoliticaRisco();
        var propriedades = new PropriedadesModelosRisco(
                "regras-transacionais", "1.0.0", false,
                null, null, 80, null);

        var experimento = configuracao.experimentoModelosRisco(
                configuracao.politicaRisco(politica(70)),
                propriedades);

        assertThat(experimento.percentualAmostragem()).isZero();
        assertThat(experimento.avaliarChallenger(
                new ContextoRisco(new BigDecimal("100.00"), "BR", 0, 0)))
                .isEmpty();
    }

    @Test
    void deveRecusarConfiguracaoIncompletaOuAmostragemInvalida() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PropriedadesModelosRisco(
                        "regras", "1.0.0", true,
                        "regras", "1.1.0", 101, politica(65)))
                .withMessageContaining("amostragem");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PropriedadesModelosRisco(
                        "regras", "1.0.0", true,
                        " ", "1.1.0", 10, politica(65)))
                .withMessageContaining("nomeChallenger");
    }

    private PropriedadesPoliticaRisco politica(int limiteReprovacao) {
        return new PropriedadesPoliticaRisco(
                new BigDecimal("1000.00"),
                new BigDecimal("5000.00"),
                20,
                50,
                "BR",
                30,
                3,
                Duration.ofMinutes(10),
                40,
                2,
                Duration.ofHours(24),
                45,
                limiteReprovacao);
    }
}
