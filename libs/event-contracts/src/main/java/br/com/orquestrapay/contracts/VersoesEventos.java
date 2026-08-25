package br.com.orquestrapay.contracts;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public final class VersoesEventos {

    public static final int VERSAO_INICIAL = 1;

    private VersoesEventos() {
    }

    public static void exigirSuportada(EventoSaga evento, int... versoesSuportadas) {
        Objects.requireNonNull(evento, "O evento e obrigatorio");
        if (versoesSuportadas.length == 0) {
            throw new IllegalArgumentException("Informe ao menos uma versao suportada");
        }

        Set<Integer> versoes = new LinkedHashSet<>();
        Arrays.stream(versoesSuportadas).forEach(versoes::add);
        if (!versoes.contains(evento.getVersao())) {
            throw new ExcecaoVersaoEventoNaoSuportada(
                    evento.getTipo(),
                    evento.getVersao(),
                    versoes);
        }
    }
}
