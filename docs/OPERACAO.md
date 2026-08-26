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

## Laboratório de caos

Inicie a bancada com os proxies de falha e execute o ensaio automatizado:

```powershell
docker compose -f compose.yml -f compose.caos.yml up -d --wait
.\scripts\testar-caos-recuperacao.ps1
```

O script sempre reabilita os proxies no bloco de finalização. Para devolver os
contêineres ao modo normal, com conexões diretas, use:

```powershell
docker compose -f compose.yml up -d --wait
docker compose -f compose.yml -f compose.caos.yml rm -sf toxiproxy
```

Esse procedimento preserva todos os volumes. Em uma máquina com poucos núcleos,
aguarde a saúde dos serviços ou inicie as aplicações sequencialmente.

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

## Acessos locais

| Recurso | Endereço | Credenciais de desenvolvimento |
|---|---|---|
| Swagger do checkout | http://localhost:8080/swagger-ui.html | sem login local |
| Swagger do estoque | http://localhost:8081/swagger-ui.html | sem login local |
| Swagger do risco | http://localhost:8082/swagger-ui.html | sem login local |
| Swagger do pagamento | http://localhost:8083/swagger-ui.html | sem login local |
| Swagger da razão | http://localhost:8084/swagger-ui.html | sem login local |
| Swagger da notificação | http://localhost:8085/swagger-ui.html | sem login local |
| Grafana | http://localhost:3010 | `admin` / `orquestrapay` |
| Prometheus | http://localhost:9090 | sem login local |
| Apicurio Registry | http://localhost:8088 | sem login local |
| Mailpit | http://localhost:8025 | sem login local |
| WireMock | http://localhost:8092/__admin/requests | sem login local |

Os bancos usam `orquestrapay` como usuário e senha somente no Compose. As portas
`5433` a `5438` pertencem aos seis serviços, e `5439` ao registro de esquemas.
Não exponha essas credenciais ou o perfil sem autenticação à internet.

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

Use a mesma consulta nas portas/bancos dos outros serviços. Linhas com muitas
tentativas ou em quarentena indicam falha que precisa de investigação. Prefira a
API administrativa para reprocessar ou descartar definitivamente, pois ela exige
motivo, preserva o erro anterior, registra o responsável e mantém a ordem dos
eventos da compra.

Para provar que uma versão de contrato incompatível não produz efeito parcial e
é encaminhada à DLT:

```powershell
.\scripts\testar-versao-evento-dlt.ps1
```

O Compose executa `registrador-esquemas` antes dos seis serviços. Esse processo
consulta o conteúdo antes de escrever e pode ser repetido sem criar versões ou
erros de unicidade:

```powershell
docker compose run --rm --no-deps registrador-esquemas
```

Os serviços operam com `REGISTRO_AUTO_CADASTRAR=false`. No Kubernetes, o Job de
bootstrap registra os contratos e os init containers aguardam o conteúdo exato
antes de liberar cada pod. Se o bootstrap falhar, investigue primeiro
`registro-esquemas`, `banco-registro` e o Job `registrar-esquemas-*`.

## Entrega de email

O ambiente local usa Mailpit em `http://localhost:8025` e SMTP na porta `1025`.
O trabalhador reivindica notificações com `SKIP LOCKED` e token de lease, envia
fora da transação e só então registra `ENVIADA`. Falhas de transporte retornam a
mensagem para `PENDENTE` com backoff exponencial e erro sanitizado.

```powershell
.\scripts\testar-envio-email-real.ps1
```

Esse ensaio cria uma compra real, confere destinatário, assunto, corpo,
`Message-ID`, cabeçalhos de correlação e horário UTC. Depois interrompe o Mailpit,
comprova que não houve falso positivo, restaura o SMTP e aguarda a recuperação.

A garantia é de entrega **pelo menos uma vez**: se o SMTP aceitar a mensagem e a
aplicação cair antes de confirmar o banco, pode ocorrer reenvio. O `Message-ID`
estável por notificação permite deduplicação pelo provedor ou pelo destinatário.
Em produção, configure `SMTP_*` para um serviço transacional com TLS,
autenticação, reputação de domínio e monitoramento de rejeições.

## Retenção automática

A limpeza operacional vem habilitada com políticas conservadoras:

| Registro | Retenção padrão |
|---|---:|
| inbox `evento_processado` | 90 dias |
| outbox publicada | 7 dias |
| quarentena e auditoria | 365 dias |
| chave HTTP de idempotência | 90 dias |
| comparação experimental de risco | 90 dias |

Os prazos e o tamanho do lote são configuráveis por `RETENCAO_EVENTOS_*`,
`RETENCAO_CHECKOUT_*` e `RISCO_COMPARACOES_RETENCAO_*`. Não reduza a retenção da
inbox para menos que a retenção do Kafka mais a janela máxima de replay. As métricas
`orquestrapay_retencao_*_total` e
`orquestrapay_risco_retencao_comparacoes_removidas_total` mostram quantas linhas
foram removidas.

## Diagnóstico rápido

| Sintoma | Verificação |
|---|---|
| API não abre | `docker compose ps` e log do serviço |
| Saga parada após uma etapa | outbox do produtor, tópico Kafka e inbox do consumidor |
| Apicurio indisponível | saúde de `banco-registro`, log de `registro-esquemas` e bootstrap `registrador-esquemas` |
| Pagamento demorando | circuit breaker, log do provedor e trace distribuído |
| PIX não conclui | callback do provedor, assinatura HMAC e expiração da cobrança |
| Fallback não ocorre | use falha técnica; recusa do emissor não deve trocar provedor |
| Webhook não chega | histórico de entregas, URL permitida e WireMock em `8092` |
| Email não chega | fila `notificacao`, métrica `orquestrapay_notificacoes_smtp_total` e Mailpit em `8025` |
| Evento vai para DLT | versão do contrato, erro da quarentena e compatibilidade do consumidor |
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
