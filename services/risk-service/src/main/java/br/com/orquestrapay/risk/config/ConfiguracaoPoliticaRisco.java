package br.com.orquestrapay.risk.config;

import br.com.orquestrapay.risk.domain.ExperimentoModelosRisco;
import br.com.orquestrapay.risk.domain.ModeloRisco;
import br.com.orquestrapay.risk.domain.PoliticaRisco;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
        PropriedadesPoliticaRisco.class,
        PropriedadesModelosRisco.class
})
public class ConfiguracaoPoliticaRisco {

    @Bean
    PoliticaRisco politicaRisco(PropriedadesPoliticaRisco propriedades) {
        return propriedades.paraDominio();
    }

    @Bean
    ExperimentoModelosRisco experimentoModelosRisco(
            PoliticaRisco politicaChampion,
            PropriedadesModelosRisco propriedades) {
        var champion = new ModeloRisco(
                propriedades.nomeChampion(),
                propriedades.versaoChampion(),
                politicaChampion);
        ModeloRisco challenger = propriedades.challengerHabilitado()
                ? new ModeloRisco(
                        propriedades.nomeChallenger(),
                        propriedades.versaoChallenger(),
                        propriedades.politicaChallenger().paraDominio())
                : null;
        int amostragem = propriedades.challengerHabilitado()
                ? propriedades.percentualAmostragem()
                : 0;
        return new ExperimentoModelosRisco(champion, challenger, amostragem);
    }
}
