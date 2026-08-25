# ADR 0005: publicação da outbox por polling concorrente

- Estado: aceito
- Data: 2026-08-25

## Contexto

Cada um dos seis domínios grava seus eventos na própria outbox PostgreSQL. A publicação poderia usar polling da aplicação ou CDC com Debezium e Kafka Connect. CDC reduz consultas periódicas ao banco, mas exigiria um conector e sua operação para cada banco, além de tratamento de WAL, transformações, roteamento, atualizações de esquema e recuperação do estado dos conectores.

Nos testes distribuídos atuais, a publicação por polling esvaziou o backlog após interrupção de consumidor, preservou a ordem por compra e não produziu efeitos financeiros duplicados. A idade p95 da outbox permanece abaixo do SLO de cinco segundos.

## Decisão

Manter o publicador por polling nesta versão. Cada réplica:

1. reivindica lotes com lease e `FOR UPDATE SKIP LOCKED`;
2. seleciona somente o evento pendente mais antigo de cada compra;
3. publica compras diferentes em paralelo com concorrência limitada;
4. confirma o lote publicado em uma transação curta;
5. devolve eventos ao fluxo após expiração do lease;
6. move falhas esgotadas para quarentena auditável.

KEDA escala consumidores pelo lag dos tópicos. Métricas de quantidade pendente, idade do evento mais antigo, publicação, falha e quarentena permitem verificar o limite real da estratégia.

## Gatilhos para reavaliar CDC

Debezium deve ser testado em um ADR substituto quando ao menos uma destas condições ocorrer sob carga representativa:

- consultas da outbox consumirem mais de 15% da capacidade do banco proprietário;
- idade p95 de publicação ultrapassar cinco segundos de forma sustentada;
- o pico contratado não for atendido após ajuste de lote, intervalo e concorrência;
- houver necessidade de distribuir mudanças de tabelas legadas que não controlam uma outbox;
- a equipe assumir capacidade operacional para Kafka Connect, slots de replicação e recuperação de conectores.

## Consequências

A solução permanece simples de operar, transacional e independente de um cluster Kafka Connect. Existe carga periódica conhecida nos bancos, monitorada por SLO e alertas. Uma queda após o envio e antes da confirmação ainda pode duplicar mensagens; por isso CDC não substituiria inbox, chaves estáveis nem idempotência dos efeitos externos.
