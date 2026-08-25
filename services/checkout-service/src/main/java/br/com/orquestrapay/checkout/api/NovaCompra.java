package br.com.orquestrapay.checkout.api;

import java.util.List;

import br.com.orquestrapay.checkout.validation.MoedaIso;
import br.com.orquestrapay.contracts.MetodoPagamento;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record NovaCompra(
        @NotBlank @Size(max = 120)
        @Pattern(regexp = "[^\\p{Cc}\\p{Cf}]*", message = "nao deve conter caracteres de controle")
        @Schema(example = "cliente-001") String idCliente,
        @NotBlank
        @Email(
                regexp = "^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$",
                message = "deve ser um email valido")
        @Size(max = 254)
        @Schema(example = "cliente@exemplo.com") String emailCliente,
        @NotBlank @MoedaIso @Schema(example = "BRL") String moeda,
        @NotBlank @Pattern(regexp = "[A-Z]{2}") @Schema(example = "BR") String pais,
        @NotBlank @Size(max = 160)
        @Pattern(regexp = "[^\\p{Cc}\\p{Cf}]*", message = "nao deve conter caracteres de controle")
        @Schema(example = "dispositivo-001") String identificadorDispositivo,
        @Size(max = 180)
        @Pattern(regexp = "[^\\p{Cc}\\p{Cf}]*", message = "nao deve conter caracteres de controle")
        @Schema(example = "tok_aprovado") String tokenPagamento,
        @NotNull @Size(min = 1, max = 50) List<@Valid NovoItemCompra> itens,
        @Schema(defaultValue = "CARTAO", example = "CARTAO") MetodoPagamento metodoPagamento,
        @Min(1) @Max(12) @Schema(defaultValue = "1", example = "1") Integer parcelas) {

    public NovaCompra(
            String idCliente,
            String emailCliente,
            String moeda,
            String pais,
            String identificadorDispositivo,
            String tokenPagamento,
            List<NovoItemCompra> itens) {
        this(
                idCliente,
                emailCliente,
                moeda,
                pais,
                identificadorDispositivo,
                tokenPagamento,
                itens,
                MetodoPagamento.CARTAO,
                1);
    }

    public NovaCompra {
        if (itens != null) {
            itens = List.copyOf(itens);
        }
        metodoPagamento = metodoPagamento == null ? MetodoPagamento.CARTAO : metodoPagamento;
        parcelas = parcelas == null ? 1 : parcelas;
    }
}
