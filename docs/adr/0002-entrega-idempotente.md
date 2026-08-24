# ADR 0002: entrega pelo menos uma vez com efeito idempotente

- Estado: aceito
- Data: 2026-08-23

## Contexto

Não é possível confirmar atomicamente uma alteração PostgreSQL e uma publicação Kafka sem aumentar muito o acoplamento e a complexidade operacional.

## Decisão

Cada serviço grava negócio e outbox na mesma transação. Consumidores registram uma inbox junto do efeito. Duplicações são esperadas e tratadas.

## Consequências

Uma queda não perde eventos confirmados. O sistema não anuncia “exatamente uma vez”; cada novo consumidor precisa demonstrar idempotência e usar uma identidade estável para efeitos externos.
