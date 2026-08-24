package br.com.orquestrapay.checkout.api;

import java.util.List;

import br.com.orquestrapay.checkout.validation.MoedaIso;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record NovaCompra(
        @NotBlank @Size(max = 120) String idCliente,
        @NotBlank @Email @Size(max = 254) String emailCliente,
        @NotBlank @MoedaIso String moeda,
        @NotBlank @Pattern(regexp = "[A-Z]{2}") String pais,
        @NotBlank @Size(max = 160) String identificadorDispositivo,
        @NotBlank @Size(max = 180) String tokenPagamento,
        @NotNull @Size(min = 1, max = 50) List<@Valid NovoItemCompra> itens) {

    public NovaCompra {
        if (itens != null) {
            itens = List.copyOf(itens);
        }
    }
}
