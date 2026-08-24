package br.com.orquestrapay.inventory.api;

import java.time.Instant;
import java.util.UUID;

public record RespostaEstoque(
        UUID idEmpresa,
        UUID idProduto,
        int quantidadeDisponivel,
        int quantidadeReservada,
        Instant atualizadoEm) {
}
