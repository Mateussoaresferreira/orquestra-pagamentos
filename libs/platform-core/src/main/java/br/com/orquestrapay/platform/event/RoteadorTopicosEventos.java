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

public class RoteadorTopicosEventos {

    private final PropriedadesEventos.Topicos topicos;

    public RoteadorTopicosEventos(PropriedadesEventos propriedades) {
        this.topicos = propriedades.topicos();
    }

    public String destino(String tipo) {
        return switch (tipo) {
            case RESERVAR_ESTOQUE, LIBERAR_ESTOQUE -> topicos.estoque();
            case ANALISAR_RISCO -> topicos.risco();
            case AUTORIZAR_PAGAMENTO, ESTORNAR_PAGAMENTO -> topicos.pagamento();
            case REGISTRAR_LANCAMENTOS -> topicos.razao();
            case ESTOQUE_RESERVADO, ESTOQUE_RECUSADO, ESTOQUE_LIBERADO,
                    RISCO_APROVADO, RISCO_REPROVADO,
                    PAGAMENTO_AUTORIZADO, PAGAMENTO_PENDENTE,
                    PAGAMENTO_RECUSADO, PAGAMENTO_ESTORNADO,
                    LANCAMENTOS_REGISTRADOS, LANCAMENTOS_RECUSADOS -> topicos.checkout();
            case COMPRA_CONCLUIDA, COMPRA_RECUSADA, COMPRA_COMPENSADA -> topicos.notificacao();
            default -> throw new IllegalArgumentException("Tipo de evento sem destino Kafka: " + tipo);
        };
    }

    public String destinoOuDesconhecido(String tipo) {
        try {
            return destino(tipo);
        } catch (IllegalArgumentException excecao) {
            return "desconhecido";
        }
    }
}
