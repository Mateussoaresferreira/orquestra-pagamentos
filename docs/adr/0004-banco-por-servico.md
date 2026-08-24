# ADR 0004: propriedade de dados por serviço

- Estado: aceito
- Data: 2026-08-23

## Contexto

Compartilhar tabelas permitiria atalhos de consulta, mas acoplaria deploy, migrations e regras de negócio entre serviços.

## Decisão

Cada serviço possui banco lógico e migrations próprios. Integrações entre limites acontecem por contratos assíncronos ou APIs públicas.

## Consequências

Os serviços podem evoluir de forma independente e as fronteiras ficam verificáveis. Consultas compostas exigem projeções, APIs ou observabilidade; não são resolvidas com joins entre bancos.
