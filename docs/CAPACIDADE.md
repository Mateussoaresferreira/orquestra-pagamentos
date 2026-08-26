# Capacidade e escalabilidade

## Meta de referência para produção

A arquitetura cloud foi dimensionada como ponto de partida para 10 milhões de
requisições HTTP e 1 milhão de compras por dia, com picos planejados de 2.000
requisições HTTP por segundo e 250 novas compras por segundo. A relação entre
pico e média é deliberadamente alta para representar campanhas e eventos
promocionais. Esses números são uma meta a comprovar em homologação, não uma
garantia produzida pelo código ou pelo Terraform.

O SLO de referência é 99,95% em uma região e três zonas de disponibilidade. A
necessidade de 99,99%, continuidade durante a perda de uma região ou público
global muda o problema e exige um desenho multirregional próprio.

## O que foi medido

A bancada local usa uma instância de cada serviço, seis tópicos Kafka com 12 partições cada e os
bancos PostgreSQL em contêineres na mesma máquina. O cenário padrão aumenta a
entrada gradualmente, sustenta 25 compras por segundo e depois reduz a carga.

Em 24 de agosto de 2026, a execução final iniciada com a bancada completamente
convergida produziu:

- 2.099 compras aceitas com `202` e 2.199 requisições HTTP no total;
- zero falhas HTTP, respostas inesperadas, respostas `429` ou iterações
  descartadas;
- latência média de 79,49 ms, p90 de 201,40 ms, p95 de 315,45 ms e máximo de
  1,00 s;
- no máximo 14 usuários virtuais ativos durante a geração;
- convergência das 2.099 sagas em 192 segundos depois do término da carga;
- estoque conferido, partidas dobradas balanceadas e nenhuma outbox pendente ou
  mensagem em quarentena na auditoria final.

A aceitação HTTP sustentou o pico programado, mas a conclusão de negócio
continuou depois que o gerador parou. Portanto, uma réplica local absorve esse
perfil como rajada; o resultado não demonstra que ela conclui indefinidamente
25 sagas por segundo sem formar backlog.

O resultado comprova o comportamento nesse perfil e nessa máquina. Ele não é
uma certificação de milhões de usuários simultâneos.

## Recursos observados

Uma amostra durante a subida da carga registrou o checkout perto de um núcleo
de CPU e 445,6 MiB do limite de 512 MiB. Pagamento e razão chegaram a 56,7% e
48,3% de CPU; Kafka usou aproximadamente 842 MiB. Não houve estouro dos limites
de memória, reinício de contêiner ou perda de saúde. O checkout é o primeiro
candidato a escala horizontal nesse perfil.

## Carga distribuída e interrupção

Dois geradores independentes executaram 589 compras na mesma janela. Todas
foram aceitas, sem `429`, erro HTTP ou iteração descartada; o p95 de cada
gerador ficou entre 166 e 189 ms. Uma segunda execução curta, já com a auditoria
integrada ao script, criou 53 compras e terminou com todos os domínios
consistentes.

No ensaio final de interrupção, o serviço de pagamento foi encerrado à força
durante a entrada de carga. O checkout aceitou 319 compras sem falha HTTP,
resposta inesperada ou limitação, com p95 de 315,68 ms. O backlog permaneceu
durável enquanto o consumidor estava ausente e convergiu 87 segundos depois da
reinicialização. Nenhuma compra recebeu efeito financeiro duplicado e a
auditoria final encontrou os seis bancos, estoque, outboxes e partidas dobradas
consistentes.

Esses testes comprovam distribuição da geração, recuperação de consumidor e
idempotência sob redelivery na bancada local. Eles ainda não substituem um soak
test distribuído em infraestrutura equivalente à produção.

## Proteção contra sobrecarga

O checkout não depende mais de a latência crescer até o cliente desistir. A
borda aplica limite por IP em uma janela explícita; o Redis consome, de forma
atômica, um token da cota global e outro da empresa; e cada réplica possui um
teto de compras em processamento. Ao atingir um desses limites, a API devolve
`429 Too Many Requests` com `Retry-After` e métricas identificando se a recusa
ocorreu no controle distribuído ou na capacidade local.

Os valores de referência do chart são 60 compras por empresa a cada minuto,
300 compras por segundo no conjunto e 16 compras simultâneas por réplica.
Esses números são pontos de partida e devem ser recalibrados com teste de carga,
capacidade contratada dos provedores e limite de conexões do banco.

## Como reproduzir

```powershell
.\scripts\testar-carga.ps1
```

O script exige uma bancada previamente convergida, registra quantas compras a
execução criou, executa o k6, espera a fila assíncrona drenar e então compara os
seis bancos. A execução falha se a API ultrapassar os limites de latência/erro,
se a saga não convergir em cinco minutos ou se houver qualquer inconsistência.

Para um ensaio mais longo:

```powershell
.\scripts\testar-carga.ps1 -TaxaPico 25 -DuracaoSustentacao 10m `
  -TempoMaximoConvergenciaSegundos 900
```

Para gerar carga a partir de processos independentes e depois auditar toda a
saga:

```powershell
.\scripts\testar-carga-distribuida.ps1 -QuantidadeGeradores 2
```

Para interromper um consumidor no meio da carga, reiniciá-lo e provar que o
backlog converge sem duplicar pagamentos:

```powershell
.\scripts\testar-interrupcao-consumidor.ps1
```

## Volume exato ao longo do tempo

O cenário anterior encontra comportamento sob uma curva de carga. Para provar
uma quantidade acumulada, use `testar-volume.ps1`. Esse segundo ensaio não tenta
manter milhões de conexões abertas: ele cria uma quantidade **exata** de compras
reais, divide o trabalho em lotes e só avança depois que a saga anterior
converge.

Comece por uma calibração pequena:

```powershell
.\scripts\testar-volume.ps1 `
  -TotalCompras 1000 `
  -TamanhoLote 250 `
  -TaxaAlvo 10 `
  -Usuarios 5 `
  -QuantidadeEmpresas 20 `
  -IdExecucao calibracao-1000
```

O ensaio completo de um milhão fica preparado assim:

```powershell
.\scripts\testar-volume.ps1 `
  -TotalCompras 1000000 `
  -TamanhoLote 10000 `
  -TaxaAlvo 25 `
  -Usuarios 20 `
  -QuantidadeEmpresas 100 `
  -IdExecucao volume-um-milhao `
  -AmbienteDedicado
```

Um milhão de entradas a 25 por segundo exige no mínimo 11 horas, 6 minutos e
40 segundos somente para o ingresso HTTP. A bancada local medida concluiu o
fluxo ponta a ponta em uma taxa menor que a aceitação; por isso, com drenagem e
auditorias entre lotes, a execução pode passar de 40 horas. Esse volume também
gera muitos milhões de linhas, eventos Kafka, WAL, logs e métricas. Faça antes a
calibração, meça bytes por compra e reserve dezenas de gigabytes em um ambiente
isolado. O parâmetro `-AmbienteDedicado` evita iniciar acidentalmente um ensaio
grande junto de operações manuais.

Cada índice recebe uma chave idempotente determinística. O checkpoint fica em
`.auditoria/volume-<id>/estado.json` e só é avançado depois da auditoria do lote.
Se a máquina ou o k6 parar depois de aceitar parte das chamadas, repita:

```powershell
.\scripts\testar-volume.ps1 -IdExecucao volume-um-milhao
```

O lote incompleto é reenviado com as mesmas chaves: compras já aceitas viram
replays e apenas as ausentes são criadas. Para executar uma janela por vez, use
`-MaximoLotesNestaExecucao 1` e retome depois com o mesmo identificador.

A comprovação final exige simultaneamente o total exato no checkout, reservas,
análises aprovadas, pagamentos autorizados, operações concluídas, transações
contábeis, dois lançamentos por compra e notificações enviadas. O produto
exclusivo do ensaio também precisa apresentar saldo disponível e reservado
compatíveis, com todas as outboxes vazias e nenhuma quarentena.

## Leitura correta de “milhões”

Uma taxa sustentada de 25 compras por segundo corresponde aritmeticamente a
2,16 milhões de inícios de compra por dia. Isso não significa que o ambiente
foi testado durante um dia, nem que suporta milhões de conexões simultâneas. O
resultado só pode ser chamado de “um milhão processado” quando
`testar-volume.ps1` concluir o checkpoint de `1000000/1000000` e aprovar a
auditoria agregada final.
Cada compra ainda gera diversos eventos, escritas, chamadas ao provedor e uma
notificação. O ensaio local comprovou absorção de rajada e convergência
posterior; não comprovou processamento ponta a ponta contínuo nessa taxa.

A arquitetura permite escala horizontal porque os serviços não compartilham
memória de processo, as operações são idempotentes e cada tópico Kafka possui
12 partições. O KEDA aumenta cada serviço até 12 réplicas combinando CPU com o
lag do seu grupo Kafka. Quando faltam máquinas para os novos pods, o Karpenter
provisiona capacidade Spot ou sob demanda em três zonas; uma fila SQS antecipa
o dreno de interrupções Spot. O grupo EKS gerenciado permanece como base sob
demanda para Karpenter, KEDA e componentes essenciais. O limite efetivo será
o menor entre:

- partições e atraso dos consumidores Kafka;
- conexões, CPU, IOPS e contenção do PostgreSQL;
- capacidade e limites dos provedores de pagamento;
- throughput do Redis e do registro de esquemas;
- recursos dos pods e velocidade das filas de trabalho;
- rede, balanceador e cotas da conta cloud.

No perfil Kubernetes, cada pod abre um consumidor por grupo. Assim, 12 réplicas
ocupam exatamente as 12 partições sem criar consumidores ociosos nem ampliar
desnecessariamente os rebalanceamentos. O chart falha durante a renderização se
`maximoReplicas x concorrenciaKafka` ultrapassar a quantidade de partições. Para
elevar esse teto, aumente partições e réplicas como uma mudança de capacidade
planejada e execute novamente os ensaios de carga e redelivery.

O challenger de risco usa 10% de amostragem no perfil Kubernetes. A decisão
champion continua em 100% das compras, enquanto a avaliação experimental reduz
CPU e escritas adicionais. Comparações com mais de 90 dias são removidas em
lotes de até 5.000 por execução. Mesmo com o mínimo de duas réplicas, essa
configuração consegue remover até 240 mil comparações por dia, acima das 100 mil
geradas pela meta de um milhão de compras/dia. A análise que efetivamente
decidiu a compra permanece preservada.

Os serviços acessam o PostgreSQL por RDS Proxy com TLS, credenciais por domínio
e pool central limitado. Isso absorve picos de conexões provocados pela escala
de pods, mas não aumenta CPU, IOPS ou capacidade transacional do banco. Alarmes
disparam quando o pool supera 70% ou o empréstimo de conexão passa de 100 ms.

O perfil de produção inicia com 200 GiB e permite autoscaling até 2 TiB; o
Terraform rejeita um teto inferior a 1 TiB nesse perfil. Esse espaço é margem,
não retenção infinita. O ambiente de referência mantém os bancos lógicos em uma
instância RDS compartilhada. Quando CPU, IOPS, WAL ou contenção sustentada
atingirem o limite medido, o próximo passo é separar pagamento/razão, checkout e
demais domínios em instâncias próprias, sem mudar os contratos entre serviços.

Acima do pico contratado, WAF, cota global, cota por empresa e admissão local
devolvem `429` com `Retry-After`. Rejeitar parte da entrada de forma previsível
é preferível a deixar a latência crescer até derrubar todos os fluxos.

## Validação antes de produção

Para declarar uma capacidade contratual, execute em um ambiente de homologação
equivalente à produção:

1. carga distribuída por pelo menos 30 a 60 minutos;
2. ensaios de pico, soak, perda de pod, indisponibilidade do provedor e aumento
   de latência no banco;
3. medição do tempo completo da saga, e não apenas do `202` do checkout;
4. acompanhamento de lag por grupo/partição, pool JDBC, IOPS, CPU, memória,
   pausas de GC, erros e quarentenas;
5. aumento progressivo da taxa até encontrar o ponto de saturação e manter uma
   margem operacional definida pelo SLO.

Virtual Threads reduzem o custo de espera por I/O, mas não ampliam a capacidade
do banco, do Kafka ou do adquirente. Por isso, escala deve ser comprovada com
medição e não inferida apenas pela tecnologia usada.
