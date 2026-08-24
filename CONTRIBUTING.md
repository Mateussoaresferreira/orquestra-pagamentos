# Como contribuir

## Preparação

1. use Java 25 e Docker Desktop;
2. execute `mvnw clean verify`;
3. suba o Compose e execute `scripts/testar-cenarios.ps1`;
4. mantenha código, mensagens, tabelas e documentação em português;
5. use inglês somente em nomes de pastas e convenções obrigatórias das ferramentas.

## Mudanças de domínio

Uma alteração no fluxo da compra precisa atualizar, quando aplicável:

- contrato do evento e compatibilidade Avro;
- transições e compensações da saga;
- outbox/inbox e comportamento idempotente;
- migrations do serviço proprietário;
- testes automatizados e coleção Postman;
- métricas, alertas e documentação arquitetural.

## Qualidade mínima

- não compartilhe tabelas entre serviços;
- não faça chamada remota dentro de transação de banco;
- não registre token, segredo ou dado pessoal desnecessário;
- não introduza mensageria sem estratégia de duplicação e falha;
- escreva teste que falha antes da correção e passa depois dela;
- mantenha o escopo da mudança pequeno e explicável.

## Commits e revisão

Use mensagens objetivas em português, por exemplo `feat: adicionar conciliação de pagamentos`. Explique no pull request o problema, a decisão, como foi testado e qualquer risco operacional ou de compatibilidade.
