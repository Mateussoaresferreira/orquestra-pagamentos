package br.com.orquestrapay.checkout.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("orquestrapay.watchdog-saga")
public record PropriedadesWatchdogSaga(
        boolean habilitado,
        int tamanhoLote,
        Duration limiteRecebida,
        Duration limiteEstoqueReservado,
        Duration limiteRiscoAprovado,
        Duration limitePagamentoAutorizado,
        Duration limiteCompensando) {

    public PropriedadesWatchdogSaga {
        tamanhoLote = tamanhoLote <= 0 ? 50 : tamanhoLote;
        validar(limiteRecebida, "limiteRecebida");
        validar(limiteEstoqueReservado, "limiteEstoqueReservado");
        validar(limiteRiscoAprovado, "limiteRiscoAprovado");
        validar(limitePagamentoAutorizado, "limitePagamentoAutorizado");
        validar(limiteCompensando, "limiteCompensando");
    }

    private static void validar(Duration duracao, String nome) {
        if (duracao == null || duracao.isZero() || duracao.isNegative()) {
            throw new IllegalArgumentException(nome + " deve ser maior que zero");
        }
    }
}
