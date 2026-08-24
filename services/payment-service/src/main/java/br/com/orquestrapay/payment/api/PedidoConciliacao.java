package br.com.orquestrapay.payment.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PedidoConciliacao(
        @Size(min = 1, max = 500)
        List<@NotNull @Valid RegistroProvedor> registros) {

    public PedidoConciliacao {
        registros = registros == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(registros));
    }
}
