# ADR 0001: saga orquestrada no caminho crítico

- Estado: aceito
- Data: 2026-08-23

## Contexto

Estoque, risco, pagamento e razão contábil precisam avançar em ordem e executar compensações diferentes conforme o ponto da falha.

## Decisão

O checkout guarda o estado da saga e emite o próximo comando. Notificações usam coreografia porque não decidem o resultado da compra.

## Consequências

O fluxo e as compensações ficam explícitos e auditáveis. O checkout concentra a coordenação, por isso precisa ser pequeno, idempotente e bem testado. Nenhum participante depende de chamadas HTTP síncronas ao orquestrador.
