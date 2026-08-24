package br.com.orquestrapay.contracts;

import java.util.UUID;

public record ResultadoEstoque(
        UUID idReserva,
        boolean aprovado,
        String motivo) {
}
