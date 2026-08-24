# SLOs e alertas

## Indicadores propostos

| Jornada | Indicador | Objetivo inicial |
|---|---|---:|
| Aceitação do checkout | respostas válidas sem 5xx | 99,9% em 30 dias |
| Aceitação do checkout | latência p95 | abaixo de 750 ms |
| Saga aprovada | tempo de `RECEBIDA` a `CONCLUIDA` p95 | abaixo de 10 s |
| Compensação | tempo de `COMPENSANDO` a `COMPENSADA` p95 | abaixo de 30 s |
| Publicação | idade do evento mais antigo na outbox p95 | abaixo de 5 s |
| Consumo | eventos enviados à DLT | zero em operação normal |
| Contabilidade | transações desbalanceadas fechadas | zero absoluto |

Os números são metas iniciais. Eles devem ser recalibrados com carga representativa e expectativas do negócio.

## Alertas já configurados

O Prometheus carrega `infra/observability/prometheus/alertas.yml` com alertas para:

- serviço indisponível por dois minutos;
- taxa de erro 5xx acima de 2%;
- latência HTTP p95 acima de um segundo;
- mais de 5% das compras exigindo compensação;
- mais de 100 eventos pendentes na outbox por cinco minutos;
- evento pendente na outbox por mais de dois minutos;
- evento movido para quarentena ou enviado à DLT;
- consumidor Kafka atrasado em mais de mil registros por cinco minutos.

As métricas `orquestrapay_outbox_*` são calculadas em cada banco proprietário. Os
contadores de publicação, falha, quarentena e DLT são incrementados no ponto em
que o resultado acontece, enquanto o atraso Kafka vem do cliente oficial
instrumentado pelo Micrometer.

## Política de severidade

- **Crítica:** perda de disponibilidade, risco de cobrança sem conclusão ou quebra contábil.
- **Alta:** erros elevados, compensações anormais ou DLT crescendo.
- **Média:** degradação de latência ou capacidade antes de afetar o SLO.

## Investigação

1. confirme impacto e janela no Grafana;
2. filtre traces pelo serviço e estado HTTP;
3. use `idCompra` para correlacionar logs e histórico;
4. examine outbox, inbox e DLT;
5. verifique saturação de JDBC, Redis, Kafka e provedor;
6. só reprocese uma mensagem após entender se o consumidor continua idempotente.

## Orçamento de erro

Um SLO de 99,9% permite aproximadamente 43 minutos de indisponibilidade em 30 dias. Ao consumir rapidamente o orçamento, mudanças de risco devem ser pausadas em favor de correções de confiabilidade.
