package br.com.orquestrapay.payment.integration;

import java.util.List;

public class ExcecaoProvedoresIndisponiveis extends RuntimeException {

    private final List<String> provedoresTentados;

    public ExcecaoProvedoresIndisponiveis(List<String> provedoresTentados, Throwable causa) {
        super("Nenhum provedor de pagamento respondeu com sucesso", causa);
        this.provedoresTentados = List.copyOf(provedoresTentados);
    }

    public List<String> provedoresTentados() {
        return provedoresTentados;
    }
}
