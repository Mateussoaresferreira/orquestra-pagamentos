# Índice da documentação

Este diretório concentra a referência técnica da Orquestra de Pagamentos. O
README apresenta a proposta e o início rápido; os documentos abaixo preservam o
detalhamento necessário para desenvolvimento, revisão e operação.

## Escolha seu caminho

| Objetivo | Ordem recomendada |
|---|---|
| Entender o projeto | `ARQUITETURA` → `PAGAMENTOS-E-INTEGRACOES` → ADRs |
| Executar localmente | `OPERACAO` → `TESTES` |
| Avaliar segurança | `SEGURANCA` → `SLOS` → `TESTES` |
| Avaliar escala | `CAPACIDADE` → `IMPLANTACAO-AWS` |
| Integrar outro sistema | OpenAPI → AsyncAPI → `PAGAMENTOS-E-INTEGRACOES` |

## Guias

| Documento | Conteúdo |
|---|---|
| [Arquitetura](ARQUITETURA.md) | Limites, saga, compensação, outbox/inbox, ordenação e concorrência |
| [Pagamentos e integrações](PAGAMENTOS-E-INTEGRACOES.md) | Cartão, PIX, webhooks, conciliação, quarentena e SDK |
| [Operação local](OPERACAO.md) | Inicialização, acessos, logs, filas, retenção e recuperação |
| [Testes](TESTES.md) | Pirâmide, Postman, Testcontainers, carga e interrupções |
| [Segurança](SEGURANCA.md) | Identidade, multiempresa, criptografia, limites e ameaças |
| [Capacidade](CAPACIDADE.md) | Medições locais, metas e leitura correta de escala |
| [SLOs](SLOS.md) | Indicadores, alertas, severidade e orçamento de erro |
| [Implantação AWS](IMPLANTACAO-AWS.md) | Referência EKS, MSK, RDS, Redis, KEDA e Karpenter |

## Contratos

| Contrato | Escopo |
|---|---|
| [Checkout](openapi/contrato-checkout.json) | Compras, histórico, administração e saúde pública |
| [Estoque](openapi/contrato-estoque.json) | Saldo, reserva, liberação e administração |
| [Risco](openapi/contrato-risco.json) | Resultado, comparação de modelos e administração |
| [Pagamento](openapi/contrato-pagamento.json) | Cartão, PIX, callbacks, conciliação e divergências |
| [Razão contábil](openapi/contrato-razao.json) | Lançamentos, recebíveis e administração |
| [Notificação](openapi/contrato-notificacao.json) | Email, webhook empresarial e administração |
| [Eventos AsyncAPI](eventos-asyncapi.yml) | Tópicos Kafka, envelopes Avro e DLTs |

Os arquivos OpenAPI são cópias versionadas dos contratos gerados pelos serviços.
Com o ambiente saudável, atualize-os por:

```powershell
.\scripts\exportar-openapi.ps1
```

O CI valida a sintaxe JSON e a semântica OpenAPI dos contratos. Alterações de
endpoint devem atualizar o código, os testes, o Postman e o respectivo arquivo
OpenAPI no mesmo commit.

## Decisões arquiteturais

- [ADR 0001: saga orquestrada](adr/0001-saga-orquestrada.md)
- [ADR 0002: entrega idempotente](adr/0002-entrega-idempotente.md)
- [ADR 0003: Virtual Threads](adr/0003-virtual-threads.md)
- [ADR 0004: banco por serviço](adr/0004-banco-por-servico.md)
- [ADR 0005: outbox por polling](adr/0005-publicacao-outbox-por-polling.md)
- [ADR 0006: champion/challenger de risco](adr/0006-comparacao-modelos-risco.md)

## Políticas do repositório

- [Como contribuir](../CONTRIBUTING.md)
- [Política de segurança](../SECURITY.md)
- [Licença](../LICENSE)
