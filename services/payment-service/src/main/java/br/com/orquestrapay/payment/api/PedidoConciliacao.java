package br.com.orquestrapay.payment.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.time.Duration;
import java.time.Instant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PedidoConciliacao(
        @NotBlank @Size(max = 60)
        @Pattern(regexp = "[a-z0-9][a-z0-9-]{0,59}") String provedor,
        @NotBlank @Size(max = 100) String identificadorExtrato,
        @NotNull Instant periodoInicio,
        @NotNull Instant periodoFim,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String moeda,
        @Size(max = 500)
        List<@NotNull @Valid RegistroProvedor> registros) {

    public PedidoConciliacao {
        registros = registros == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(registros));
    }

    @AssertTrue(message = "O periodo deve ser crescente e possuir no maximo 31 dias")
    public boolean isPeriodoValido() {
        if (periodoInicio == null || periodoFim == null || !periodoInicio.isBefore(periodoFim)) {
            return false;
        }
        return Duration.between(periodoInicio, periodoFim).compareTo(Duration.ofDays(31)) <= 0;
    }
}
