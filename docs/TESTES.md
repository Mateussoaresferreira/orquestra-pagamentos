# Estratégia de testes

## Pirâmide

| Camada | O que comprova |
|---|---|
| Unidade | risco, criptografia, JWT, roteamento, HMAC, anti-SSRF e parcelamento |
| Integração | migrations e repositórios de todos os serviços com estado contra PostgreSQL real |
| Ponta a ponta | saga, idempotência, recusas, retentativas e compensação no Compose |
| Contrato | serialização Avro, JSON do Postman e configuração da infraestrutura |
| Carga | pico controlado, volume acumulado exato e comparação entre tipos de thread |

## Testes Java

```powershell
java --version
.\mvnw.cmd --batch-mode --no-transfer-progress clean verify
```

Os testes de integração precisam de Docker porque Testcontainers inicia
PostgreSQL descartável para checkout, estoque, risco, pagamento, razão e
notificação. Além das consultas, eles exercitam isolamento por empresa,
migrações e restrições de integridade, janelas de risco, PIX, estorno,
conciliação, leases, quarentena, tentativas de envio, parcelas imutáveis e
partidas dobradas.

O JaCoCo falha o `verify` quando um módulo cai abaixo do seu piso atual. Os
limites são individuais e baseados na cobertura realmente medida, entre 30% e
75%, para impedir regressões sem apresentar um número artificial de 80%. Cada
nova versão deve elevar o piso depois de ampliar testes relevantes.

| Modulo | Cobertura de linhas medida | Piso do CI |
|---|---:|---:|
| Contratos de eventos | 32,1% | 30% |
| Nucleo da plataforma | 73,3% | 65% |
| Starter Spring Boot | 72,1% | 65% |
| Checkout | 38,5% | 35% |
| Estoque | 56,7% | 50% |
| Risco | 90,3% | 75% |
| Pagamento | 62,3% | 60% |
| Razao contabil | 73,3% | 65% |
| Notificacao | 42,7% | 40% |
| Simulador de provedor | 57,0% | 50% |

A execucao integral atual possui 219 testes automatizados, sem falhas, erros ou
testes ignorados. Os percentuais acima sao evidencias do `clean verify` completo,
nao uma estimativa.

## Fluxo ponta a ponta

```powershell
.\scripts\testar-fluxo.ps1
.\scripts\testar-cenarios.ps1
.\scripts\testar-postman.ps1
.\scripts\testar-postman-rapido.ps1 -Execucoes 3 -Paralelismo 3
.\scripts\testar-seguranca.ps1
.\scripts\auditar-consistencia.ps1
.\scripts\auditar-retornos-tempos.ps1
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
dobradas e ausência de eventos pendentes ou em quarentena. Operações ainda em
andamento são validadas contra os estados intermediários permitidos e
apresentadas separadamente, sem serem confundidas com perda de dados.

`auditar-retornos-tempos.ps1` cria cenários exclusivos e valida os contratos
positivos e negativos de ponta a ponta. O script compara valores, decisões de
risco `true` e `false`, idempotência, isolamento entre empresas, recusa do
emissor, compensação e ordem causal da saga. As datas precisam estar em
ISO-8601/UTC, dentro da janela real de execução e idênticas aos registros dos
sete bancos PostgreSQL; no resumo, o mesmo período também aparece convertido
para o horário de Brasília.

`testar-dast.ps1` usa uma imagem do OWASP ZAP fixada por digest, importa os
OpenAPIs de checkout, estoque, risco, pagamento, razão e notificação e executa
uma varredura ativa contra cada aplicação local. O script falha quando encontra
qualquer alerta de risco e grava seis conjuntos de relatórios em `.auditoria`,
diretório que não é versionado.

No checkout, o HTTP Sender em
`tests/security/chave-idempotencia-dinamica.js` gera uma chave de idempotência
nova para cada sondagem ativa. Sem isso, o ZAP altera o corpo e reutiliza a
mesma chave, interpreta o conflito `409` esperado como diferença booleana e
produz falso positivo de SQL injection. O script não mantém lista de alertas
ignorados: qualquer ocorrência de risco ainda encerra a auditoria.

A execução final aplicou 119 regras em cada um dos seis contratos e terminou
sem alertas. Durante a evolução do teste, uma sonda com byte NUL revelou que um
caractere de controle podia alcançar o PostgreSQL e produzir `500`; outra sonda
mostrou que a validação permissiva de email aceitava um sufixo inválido e levava
a entrega SMTP à falha definitiva. O checkout agora rejeita ambos os casos com
`400` antes de persistir qualquer compra, e os cenários possuem testes de
regressão próprios.

O segundo script comprova:

- compra aprovada;
- replay idempotente sem nova compra;
- indisponibilidade transitória recuperada por retentativa;
- recusa por estoque insuficiente;
- recusa pelo motor de risco com liberação do estoque;
- recusa do emissor com liberação do estoque;
- falha contábil seguida de estorno e liberação;
- débitos iguais a créditos no fluxo aprovado;
- fallback técnico sem transformar recusa legítima em nova tentativa;
- PIX pendente e confirmação assíncrona assinada;
- três parcelas cuja soma coincide com o total;
- entrega de webhook registrada no receptor local;
- consulta e reprocessamento das filas operacionais.

## Postman

Importe a coleção e o ambiente em `postman`. As asserções ficam junto das
requisições e permitem acompanhar cada resposta manualmente. Como a saga é
assíncrona, aguarde o estado final antes das consultas dependentes ao executar
pastas isoladas. O script `testar-postman.ps1` injeta a chave local do provedor
em arquivo temporário, executa a coleção completa e remove o arquivo ao final.
`testar-postman-rapido.ps1` executa várias cópias isoladas da mesma coleção em
lotes concorrentes, mantém relatórios JSON em `target/auditoria-postman` e
falha quando qualquer requisição ou asserção diverge. Cada cópia cria uma
empresa própria, portanto não compartilha estoque nem chaves de idempotência.

A coleção possui 39 requisições. Ela também comprova que o champion permaneceu
como decisão real e consulta a comparação e o resumo do challenger. No fluxo
PIX, `Aguardar cobrança PIX` também
preenche a aba `Visualization` com QR Code, `txid` e Copia e Cola. As asserções
validam estrutura EMV, CRC e imagem; os testes Java decodificam o PNG e comparam
o conteúdo lido com o BR Code produzido.

## Caos e recuperação

O laboratório com Toxiproxy interrompe dependências reais da bancada sem
alterar código de produção:

```powershell
docker compose -f compose.yml -f compose.caos.yml up -d --wait
.\scripts\testar-caos-recuperacao.ps1
```

O ensaio comprova quatro cenários: retenção e drenagem da outbox durante queda do
Kafka, indisponibilidade e recuperação do banco de risco, resposta ambígua do
provedor sem cobrança no segundo adquirente e fallback somente após falha
confirmada como não processada. Cada compra precisa terminar com um pagamento,
uma transação contábil e uma comparação de risco. Ao final, o script restaura os
proxies, executa a auditoria dos seis bancos e grava evidência em `.auditoria`.

O workflow `caos-recuperacao.yml` repete o laboratório semanalmente e também
pode ser acionado manualmente no GitHub Actions.

## Carga

```powershell
.\scripts\testar-carga.ps1
.\scripts\testar-volume.ps1 -TotalCompras 1000 -TamanhoLote 250 `
  -TaxaAlvo 10 -Usuarios 5 -QuantidadeEmpresas 20 -IdExecucao calibracao-1000
```

Critérios atuais do cenário:

- menos de 1% de falhas HTTP;
- p95 inferior a 750 ms para aceitação do checkout;
- mais de 500 compras aceitas no período;
- nenhuma resposta inesperada ou limitação `429` no perfil multiempresa;
- no máximo quatro iterações não iniciadas;
- todas as sagas convergentes e os seis bancos consistentes em até cinco minutos.

O k6 mede a aceitação HTTP. O script complementar espera a conclusão assíncrona
e executa `auditar-consistencia.ps1`, evitando confundir um `202` rápido com uma
saga saudável. Resultados, limites e a interpretação correta de escala estão em
[Capacidade e escalabilidade](CAPACIDADE.md).

Para provar geração concorrente por processos independentes e recuperação após
a queda abrupta de um consumidor:

```powershell
.\scripts\testar-carga-distribuida.ps1 -QuantidadeGeradores 2
.\scripts\testar-interrupcao-consumidor.ps1
```

Os dois scripts aguardam a drenagem das filas e executam a auditoria cruzada dos
seis bancos. O segundo também rejeita qualquer pagamento duplicado depois da
reinicialização.

Na execução final de interrupção, o processo de pagamento foi encerrado
abruptamente durante a carga. Foram aceitas 319 compras, sem falha HTTP ou
resposta inesperada, com p95 de 315,68 ms. Depois da reinicialização, o backlog
convergiu em 87 segundos, sem efeito financeiro duplicado, e a auditoria dos seis
bancos foi aprovada.

`testar-volume.ps1` usa outro cenário k6, com quantidade exata e ritmo
controlado. Ele prepara um produto exclusivo, grava checkpoint por lote e pode
retomar uma interrupção sem duplicar efeitos. `auditar-volume.ps1` compara as
diferenças dos seis bancos contra os contadores capturados antes do teste e usa
consultas agregadas, sem carregar milhões de identificadores na memória do
PowerShell. A auditoria detalhada continua sendo a melhor opção para os fluxos
funcionais menores.

## Comparar Virtual Threads

```powershell
$env:SPRING_PROFILES_ACTIVE='platform-threads'
docker compose up -d --force-recreate servico-checkout servico-estoque servico-risco servico-pagamento servico-razao servico-notificacao
.\scripts\testar-carga.ps1
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

Os testes Terraform usam providers simulados e comprovam quatro situações sem
criar recursos AWS: bancada econômica válida, produção insegura rejeitada,
produção sem saída controlada rejeitada e produção resiliente válida. O último
exige RDS Proxy com TLS, classes não burstable, Karpenter com fila de
interrupções, WAF, Multi-AZ, backups e alerta antecipado do pool de conexões.
