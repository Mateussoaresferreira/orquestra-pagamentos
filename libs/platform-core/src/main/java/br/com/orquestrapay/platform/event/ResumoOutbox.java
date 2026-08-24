package br.com.orquestrapay.platform.event;

public record ResumoOutbox(
        long pendentes,
        long quarentena,
        double idadeMaisAntigaSegundos) {
}
