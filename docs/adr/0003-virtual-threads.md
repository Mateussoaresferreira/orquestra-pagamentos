# ADR 0003: Virtual Threads para I/O bloqueante

- Estado: aceito
- Data: 2026-08-23

## Contexto

O domínio usa JDBC, HTTP e bibliotecas Spring maduras com modelo bloqueante. A maior parte do tempo é espera de I/O.

## Decisão

Usar Spring MVC e JDBC sobre Virtual Threads no Java 25, preservando limites explícitos para conexões, chamadas externas e consumo Kafka.

## Consequências

O código permanece sequencial e legível com maior concorrência de espera. Virtual Threads não tornam CPU, banco ou provedor infinitos. O perfil `platform-threads` permite comparação mensurável antes de qualquer conclusão de desempenho.
