package br.com.orquestrapay.provider.api;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record PedidoEstorno(@NotNull UUID idPagamento) {
}
