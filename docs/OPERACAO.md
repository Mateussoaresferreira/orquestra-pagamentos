# Operação local

## Ciclo normal

```powershell
cd D:\JavaEstudo\portfolio-java\orquestrapay
.\scripts\iniciar.ps1
.\scripts\status.ps1
.\scripts\testar-cenarios.ps1
.\scripts\auditar-consistencia.ps1
.\scripts\parar.ps1
```

Use `-SemObservabilidade` no início quando precisar trabalhar apenas no fluxo de negócio em uma máquina com pouca memória.

## Componentes saudáveis

O comando abaixo deve mostrar os sete processos Java como `UP`:

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
docker compose logs --tail 200 servico-pagamento simulador-provedor
docker compose logs --since 10m kafka registro-esquemas
```

Comece pelo `idCompra` ou pelo `traceId`. O histórico do checkout explica as transições de domínio; Tempo mostra a cadeia distribuída; Loki mostra os detalhes técnicos.

## Consultar filas duráveis

Exemplo para o banco do checkout:

```powershell
docker compose exec banco-checkout psql -U orquestrapay -d orquestrapay_checkout -c "select tipo, tentativas, proxima_tentativa_em, publicado_em, descartado_em from evento_saida order by ocorrido_em desc limit 20;"
```

Use a mesma consulta nas portas/bancos dos outros serviços. Linhas com muitas tentativas ou em quarentena indicam falha que precisa de investigação antes de reprocessamento.

## Diagnóstico rápido

| Sintoma | Verificação |
|---|---|
| API não abre | `docker compose ps` e log do serviço |
| Saga parada após uma etapa | outbox do produtor, tópico Kafka e inbox do consumidor |
| Apicurio indisponível | saúde de `banco-registro` e log de `registro-esquemas` |
| Pagamento demorando | circuit breaker, log do provedor e trace distribuído |
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
