package br.com.orquestrapay.payment.config;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.orquestrapay.contracts.MetodoPagamento;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class TesteVinculacaoPropriedadesPagamentos {

    private final ApplicationContextRunner contexto = new ApplicationContextRunner()
            .withUserConfiguration(ConfiguracaoTeste.class);

    @Test
    void deveVincularUmProvedorCompletoAConfiguracao() {
        contexto.withPropertyValues(
                        "orquestrapay.pagamentos.provedores.principal.url=http://localhost:8090",
                        "orquestrapay.pagamentos.provedores.principal.tempo-limite-conexao=2s",
                        "orquestrapay.pagamentos.provedores.principal.tempo-limite-leitura=5s",
                        "orquestrapay.pagamentos.provedores.principal.chave-api=chave-api-provedor-para-testes",
                        "orquestrapay.pagamentos.provedores.principal.segredo-webhook=segredo-webhook-provedor-teste",
                        "orquestrapay.pagamentos.provedores.principal.prioridade=10",
                        "orquestrapay.pagamentos.provedores.principal.metodos[0]=CARTAO",
                        "orquestrapay.pagamentos.provedores.principal.metodos[1]=PIX",
                        "orquestrapay.pagamentos.provedores.principal.maximo-concorrente=12",
                        "orquestrapay.pagamentos.provedores.principal.maximo-chamadas-por-periodo=80",
                        "orquestrapay.pagamentos.provedores.principal.periodo-limite=1s")
                .run(aplicacao -> {
                    assertThat(aplicacao).hasSingleBean(PropriedadesPagamentos.class);

                    var provedor = aplicacao.getBean(PropriedadesPagamentos.class)
                            .provedores()
                            .get("principal");

                    assertThat(provedor.prioridade()).isEqualTo(10);
                    assertThat(provedor.metodos())
                            .containsExactlyInAnyOrder(MetodoPagamento.CARTAO, MetodoPagamento.PIX);
                    assertThat(provedor.maximoConcorrente()).isEqualTo(12);
                    assertThat(provedor.maximoChamadasPorPeriodo()).isEqualTo(80);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PropriedadesPagamentos.class)
    static class ConfiguracaoTeste {
    }
}
