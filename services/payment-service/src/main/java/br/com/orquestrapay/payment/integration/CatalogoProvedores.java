package br.com.orquestrapay.payment.integration;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import br.com.orquestrapay.contracts.MetodoPagamento;

public class CatalogoProvedores {

    private final Map<String, ClienteProvedor> provedores;

    public CatalogoProvedores(Map<String, ClienteProvedor> provedores) {
        this.provedores = Map.copyOf(provedores);
    }

    public Optional<ClienteProvedor> buscar(String nome) {
        if (nome == null || nome.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(provedores.get(nome.toLowerCase(Locale.ROOT)));
    }

    public List<ClienteProvedor> ordenar(MetodoPagamento metodo, String preferido) {
        String normalizado = preferido == null ? "" : preferido;
        Comparator<ClienteProvedor> comparador = Comparator
                .comparing((ClienteProvedor provedor) -> !provedor.nome().equalsIgnoreCase(normalizado))
                .thenComparingInt(ClienteProvedor::prioridade)
                .thenComparing(ClienteProvedor::nome);
        return provedores.values().stream()
                .filter(provedor -> provedor.aceita(metodo))
                .sorted(comparador)
                .toList();
    }
}
