# Operação local

## Ciclo normal

```powershell
cd orquestra-pagamentos
.\scripts\iniciar.ps1
.\scripts\status.ps1
.\scripts\testar-cenarios.ps1
.\scripts\auditar-consistencia.ps1
.\scripts\parar.ps1
```

Use `-SemObservabilidade` no início quando precisar trabalhar apenas no fluxo de negócio em uma máquina com pouca memória.

## Componentes saudáveis

O comando abaixo deve mostrar os seis serviços, os dois simuladores de provedor
e a infraestrutura necessária como `UP`:

```powershell
.\scripts\status.ps1
```

Também é possível consultar diretamente:

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health/readiness
Invoke-RestMethod http://localhost:9090/-/ready
Invoke-RestMethod http://localhost:3010/api/health
```

## Logs

```powershell
docker compose logs --tail 200 servico-checkout
docker compose logs --tail 200 servico-pagamento simulador-provedor simulador-provedor-contingencia
docker compose logs --tail 200 servico-notificacao receptor-webhook
docker compose logs --since 10m kafka registro-esquemas
```

Comece pelo `idCompra` ou pelo `traceId`. O histórico do checkout explica as transições de domínio; Tempo mostra a cadeia distribuída; Loki mostra os detalhes técnicos.

## Consultar filas duráveis

Exemplo para o banco do checkout:

```powershell
docker compose exec banco-checkout psql -U orquestrapay -d orquestrapay_checkout -c "select tipo, tentativas, proxima_tentativa_em, publicado_em, descartado_em from evento_saida order by ocorrido_em desc limit 20;"
```

Use a mesma consulta nas portas/bancos dos outros serviços. Linhas com muitas tentativas ou em quarentena indicam falha que precisa de investigação antes de reprocessamento.

## Retenção automática

A limpeza operacional vem habilitada com políticas conservadoras:

| Registro | Retenção padrão |
|---|---:|
| inbox `evento_processado` | 90 dias |
| outbox publicada | 7 dias |
| quarentena e auditoria | 365 dias |
| chave HTTP de idempotência | 90 dias |

Os prazos e o tamanho do lote são configuráveis por `RETENCAO_EVENTOS_*` e
`RETENCAO_CHECKOUT_*`. Não reduza a retenção da inbox para menos que a retenção
do Kafka mais a janela máxima de replay. As métricas
`orquestrapay_retencao_*_total` mostram quantas linhas foram removidas.

## Diagnóstico rápido

| Sintoma | Verificação |
|---|---|
| API não abre | `docker compose ps` e log do serviço |
| Saga parada após uma etapa | outbox do produtor, tópico Kafka e inbox do consumidor |
| Apicurio indisponível | saúde de `banco-registro` e log de `registro-esquemas` |
| Pagamento demorando | circuit breaker, log do provedor e trace distribuído |
| PIX não conclui | callback do provedor, assinatura HMAC e expiração da cobrança |
| Fallback não ocorre | use falha técnica; recusa do emissor não deve trocar provedor |
| Webhook não chega | histórico de entregas, URL permitida e WireMock em `8092` |
| Saga sem avançar | histórico da compra e contador do watchdog |
| Grafana vazio | targets do Prometheus, Alloy, Loki e coletor OpenTelemetry |
| Docker Desktop responde 500 | reiniciar o motor e reduzir o conjunto com `-SemObservabilidade` |

## Reinício sem perder dados

```powershell
docker compose down
docker compose up -d --wait
```

Os volumes mantêm PostgreSQL, Kafka, Redis e observabilidade.

## Limpeza total do ambiente local

O comando abaixo é destrutivo e remove todos os dados da Orquestra de Pagamentos:

```powershell
docker compose down --volumes --remove-orphans
```

Execute-o somente quando quiser recriar a bancada do zero.

## Backup e restauração

O Compose não automatiza backup. Em produção, use snapshots do RDS, retenção definida, cópia entre regiões quando exigida e testes periódicos de restauração. Backup não testado não é garantia de recuperação.
