package br.com.orquestrapay.platform.event;

import static br.com.orquestrapay.contracts.TiposEventos.ANALISAR_RISCO;
import static br.com.orquestrapay.contracts.TiposEventos.AUTORIZAR_PAGAMENTO;
import static br.com.orquestrapay.contracts.TiposEventos.COMPRA_COMPENSADA;
import static br.com.orquestrapay.contracts.TiposEventos.COMPRA_CONCLUIDA;
import static br.com.orquestrapay.contracts.TiposEventos.COMPRA_RECUSADA;
import static br.com.orquestrapay.contracts.TiposEventos.ESTOQUE_LIBERADO;
import static br.com.orquestrapay.contracts.TiposEventos.ESTOQUE_RECUSADO;
import static br.com.orquestrapay.contracts.TiposEventos.ESTOQUE_RESERVADO;
import static br.com.orquestrapay.contracts.TiposEventos.ESTORNAR_PAGAMENTO;
import static br.com.orquestrapay.contracts.TiposEventos.LANCAMENTOS_RECUSADOS;
import static br.com.orquestrapay.contracts.TiposEventos.LANCAMENTOS_REGISTRADOS;
import static br.com.orquestrapay.contracts.TiposEventos.LIBERAR_ESTOQUE;
import static br.com.orquestrapay.contracts.TiposEventos.PAGAMENTO_AUTORIZADO;
import static br.com.orquestrapay.contracts.TiposEventos.PAGAMENTO_ESTORNADO;
import static br.com.orquestrapay.contracts.TiposEventos.PAGAMENTO_PENDENTE;
import static br.com.orquestrapay.contracts.TiposEventos.PAGAMENTO_RECUSADO;
import static br.com.orquestrapay.contracts.TiposEventos.REGISTRAR_LANCAMENTOS;
import static br.com.orquestrapay.contracts.TiposEventos.RESERVAR_ESTOQUE;
import static br.com.orquestrapay.contracts.TiposEventos.RISCO_APROVADO;
import static br.com.orquestrapay.contracts.TiposEventos.RISCO_REPROVADO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class TesteRoteadorTopicosEventos {

    private final PropriedadesEventos propriedades = new PropriedadesEventos(
            true,
            PropriedadesEventos.Topicos.padrao(),
            12,
            50,
            4,
            Duration.ofSeconds(10),
            Duration.ofSeconds(30),
            12,
            Duration.ofSeconds(1),
            Duration.ofMinutes(5));
    private final RoteadorTopicosEventos roteador = new RoteadorTopicosEventos(propriedades);

    @Test
    void deveEnviarComandosApenasAoDominioResponsavel() {
        assertThat(roteador.destino(RESERVAR_ESTOQUE)).isEqualTo(propriedades.topicos().estoque());
        assertThat(roteador.destino(LIBERAR_ESTOQUE)).isEqualTo(propriedades.topicos().estoque());
        assertThat(roteador.destino(ANALISAR_RISCO)).isEqualTo(propriedades.topicos().risco());
        assertThat(roteador.destino(AUTORIZAR_PAGAMENTO)).isEqualTo(propriedades.topicos().pagamento());
        assertThat(roteador.destino(ESTORNAR_PAGAMENTO)).isEqualTo(propriedades.topicos().pagamento());
        assertThat(roteador.destino(REGISTRAR_LANCAMENTOS)).isEqualTo(propriedades.topicos().razao());
    }

    @Test
    void deveDevolverTodosOsResultadosAoCheckout() {
        assertThat(new String[] {
                ESTOQUE_RESERVADO,
                ESTOQUE_RECUSADO,
                ESTOQUE_LIBERADO,
                RISCO_APROVADO,
                RISCO_REPROVADO,
                PAGAMENTO_AUTORIZADO,
                PAGAMENTO_PENDENTE,
                PAGAMENTO_RECUSADO,
                PAGAMENTO_ESTORNADO,
                LANCAMENTOS_REGISTRADOS,
                LANCAMENTOS_RECUSADOS
        }).allSatisfy(tipo -> assertThat(roteador.destino(tipo))
                .isEqualTo(propriedades.topicos().checkout()));
    }

    @Test
    void deveEnviarEstadosFinaisSomenteParaNotificacao() {
        assertThat(new String[] {COMPRA_CONCLUIDA, COMPRA_RECUSADA, COMPRA_COMPENSADA})
                .allSatisfy(tipo -> assertThat(roteador.destino(tipo))
                        .isEqualTo(propriedades.topicos().notificacao()));
    }

    @Test
    void deveFalharQuandoUmNovoEventoNaoPossuirDestinoExplicito() {
        assertThatThrownBy(() -> roteador.destino("EVENTO_NOVO_SEM_ROTA"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sem destino");
    }
}
