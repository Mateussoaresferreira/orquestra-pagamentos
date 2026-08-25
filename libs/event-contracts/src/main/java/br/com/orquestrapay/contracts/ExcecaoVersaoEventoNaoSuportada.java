package br.com.orquestrapay.contracts;

import java.util.Set;

public class ExcecaoVersaoEventoNaoSuportada extends RuntimeException {

    private final String tipoEvento;
    private final int versaoRecebida;
    private final Set<Integer> versoesSuportadas;

    public ExcecaoVersaoEventoNaoSuportada(
            String tipoEvento,
            int versaoRecebida,
            Set<Integer> versoesSuportadas) {
        super("Versao " + versaoRecebida + " nao suportada para o evento " + tipoEvento
                + "; versoes aceitas: " + versoesSuportadas);
        this.tipoEvento = tipoEvento;
        this.versaoRecebida = versaoRecebida;
        this.versoesSuportadas = Set.copyOf(versoesSuportadas);
    }

    public String tipoEvento() {
        return tipoEvento;
    }

    public int versaoRecebida() {
        return versaoRecebida;
    }

    public Set<Integer> versoesSuportadas() {
        return versoesSuportadas;
    }
}
