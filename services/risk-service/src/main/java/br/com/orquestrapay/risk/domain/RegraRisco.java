package br.com.orquestrapay.risk.domain;

import java.util.Optional;

public sealed interface RegraRisco
        permits RegraRisco.RegraValor,
                RegraRisco.RegraPais,
                RegraRisco.RegraVelocidade,
                RegraRisco.RegraDispositivoCompartilhado {

    Optional<SinalRisco> avaliar(ContextoRisco contexto);

    record RegraValor(PoliticaRisco politica) implements RegraRisco {
        @Override
        public Optional<SinalRisco> avaliar(ContextoRisco contexto) {
            if (contexto.valorTotal().compareTo(politica.limiteValorMuitoAlto()) > 0) {
                return Optional.of(new SinalRisco(
                        "VALOR_MUITO_ALTO",
                        politica.pontosValorMuitoAlto(),
                        "Compra acima do limite muito alto de " + politica.limiteValorMuitoAlto().toPlainString()));
            }
            if (contexto.valorTotal().compareTo(politica.limiteValorAlto()) > 0) {
                return Optional.of(new SinalRisco(
                        "VALOR_ALTO",
                        politica.pontosValorAlto(),
                        "Compra acima do limite alto de " + politica.limiteValorAlto().toPlainString()));
            }
            return Optional.empty();
        }
    }

    record RegraPais(PoliticaRisco politica) implements RegraRisco {
        @Override
        public Optional<SinalRisco> avaliar(ContextoRisco contexto) {
            return politica.paisBase().equalsIgnoreCase(contexto.pais())
                    ? Optional.empty()
                    : Optional.of(new SinalRisco(
                            "PAIS_DIVERGENTE",
                            politica.pontosPaisDivergente(),
                            "Pais da compra diferente do pais base " + politica.paisBase()));
        }
    }

    record RegraVelocidade(PoliticaRisco politica) implements RegraRisco {
        @Override
        public Optional<SinalRisco> avaliar(ContextoRisco contexto) {
            return contexto.comprasRecentesCliente() >= politica.limiteComprasRecentes()
                    ? Optional.of(new SinalRisco(
                            "ALTA_VELOCIDADE",
                            politica.pontosAltaVelocidade(),
                            "Limite de compras recentes do cliente atingido"))
                    : Optional.empty();
        }
    }

    record RegraDispositivoCompartilhado(PoliticaRisco politica) implements RegraRisco {
        @Override
        public Optional<SinalRisco> avaliar(ContextoRisco contexto) {
            return contexto.clientesRecentesNoDispositivo() >= politica.limiteClientesPorDispositivo()
                    ? Optional.of(new SinalRisco(
                            "DISPOSITIVO_COMPARTILHADO",
                            politica.pontosDispositivoCompartilhado(),
                            "Limite de clientes recentes no dispositivo atingido"))
                    : Optional.empty();
        }
    }
}
