# Estratégia de testes

## Pirâmide

| Camada | O que comprova |
|---|---|
| Unidade | regras de risco, criptografia, JWT e comportamento do provedor |
| Integração | migrations e repositórios de todos os serviços com estado contra PostgreSQL real |
| Ponta a ponta | saga, idempotência, recusas, retentativas e compensação no Compose |
| Contrato | serialização Avro, JSON do Postman e configuração da infraestrutura |
| Carga | capacidade do checkout e comparação entre tipos de thread |

## Testes Java

```powershell
java --version
.\mvnw.cmd --batch-mode --no-transfer-progress clean verify
```

Os testes de integração precisam de Docker porque Testcontainers inicia PostgreSQL descartável para checkout, estoque, risco, pagamento, razão e notificação. Além das consultas, eles exercitam isolamento por empresa, migrações e restrições de integridade, janelas de risco, estorno, conciliação, tentativas de envio, imutabilidade contábil e partidas dobradas.

## Fluxo ponta a ponta

```powershell
.\scripts\testar-fluxo.ps1
.\scripts\testar-cenarios.ps1
.\scripts\testar-seguranca.ps1
.\scripts\auditar-consistencia.ps1
.\scripts\testar-dast.ps1
```

`testar-seguranca.ps1` executa verificações adversariais reproduzíveis contra a
bancada: conteúdo típico de SQL injection é persistido como texto por consultas
parametrizadas, outra empresa recebe `404`, idempotência conflitante recebe
`409`, limites HTTP são aplicados, Redis e provedor exigem credenciais e o token
de pagamento não pode aparecer em texto puro no PostgreSQL nem nos logs.

`auditar-consistencia.ps1` compara os seis bancos depois que os eventos
estabilizam. Ele confere identificadores, empresa, valores, vínculos entre
reserva, pagamento e transação, estados terminais, saldo reservado, partidas
dobradas e ausência de eventos pendentes ou em quarentena.

`testar-dast.ps1` usa uma imagem do OWASP ZAP fixada por digest, importa o
OpenAPI do checkout e executa uma varredura ativa contra a aplicação local. O
script falha quando encontra qualquer alerta de risco e grava os relatórios em
`.auditoria`, diretório que não é versionado.

O segundo script comprova:

- compra aprovada;
- replay idempotente sem nova compra;
- indisponibilidade transitória recuperada por retentativa;
- recusa por estoque insuficiente;
- recusa pelo motor de risco com liberação do estoque;
- recusa do emissor com liberação do estoque;
- falha contábil seguida de estorno e liberação;
- débitos iguais a créditos no fluxo aprovado.

## Postman

Importe a coleção e o ambiente em `postman`. As asserções ficam junto das requisições e permitem acompanhar cada resposta manualmente. Como a saga é assíncrona, aguarde o estado final antes das consultas dependentes ao executar pastas isoladas.

## Carga

```powershell
docker compose --profile carga run --rm k6
```

Critérios atuais do cenário:

- menos de 1% de falhas HTTP;
- p95 inferior a 750 ms para aceitação do checkout;
- mais de 100 compras aceitas no período.

O teste mede a aceitação HTTP, não o tempo completo da saga. O tempo fim a fim deve ser acompanhado por métricas de domínio e traces.

## Comparar Virtual Threads

```powershell
$env:SPRING_PROFILES_ACTIVE='platform-threads'
docker compose up -d --force-recreate servico-checkout servico-estoque servico-risco servico-pagamento servico-razao servico-notificacao
docker compose --profile carga run --rm k6
Remove-Item Env:SPRING_PROFILES_ACTIVE
```

Repita a carga com os mesmos recursos e compare throughput, p95, memória, conexões JDBC e número de threads. Virtual Threads ajudam esperas de I/O, mas não removem limites do banco ou do provedor.

## Infraestrutura e contratos

```powershell
docker compose config --quiet
helm lint infra/kubernetes/helm/orquestrapay
terraform -chdir=infra/terraform/aws fmt -check
terraform -chdir=infra/terraform/aws init -backend=false
terraform -chdir=infra/terraform/aws validate
terraform -chdir=infra/terraform/aws test
```

Os testes Terraform usam providers simulados e comprovam três situações sem criar recursos AWS: bancada econômica válida, produção insegura rejeitada e produção resiliente válida.
