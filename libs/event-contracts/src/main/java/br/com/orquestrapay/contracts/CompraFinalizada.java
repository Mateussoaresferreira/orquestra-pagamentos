package br.com.orquestrapay.contracts;

public record CompraFinalizada(
        String status,
        String motivo,
        String destinatario) {
}
