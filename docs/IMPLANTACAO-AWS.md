# Implantação de referência na AWS

## Objetivo

A infraestrutura em `infra/terraform/aws` representa uma topologia de produção para estudo e portfólio. Ela não é criada durante os testes locais e não deve ser aplicada sem revisar custos, limites e segurança da conta.

## Recursos modelados

- VPC com sub-redes públicas e privadas em três zonas;
- NAT Gateway para saída dos workloads privados;
- EKS com grupo gerenciado mínimo, Karpenter, KEDA, Pod Identity, IRSA e Secrets criptografados por KMS;
- ECR para as sete imagens;
- RDS PostgreSQL Multi-AZ criptografado e RDS Proxy com TLS e credencial por domínio;
- ElastiCache Redis com criptografia, TLS, autenticação e failover configurável;
- MSK Serverless com autenticação IAM;
- Cognito com escopos, grupos e clientes para PKCE e máquina a máquina;
- WAFv2 com limite por IP e janela explícita, reputação, entradas maliciosas
  conhecidas e logs;
- Secrets Manager para banco, Redis, criptografia e integração com o provedor;
- SNS criptografado por chave KMS própria e alarmes CloudWatch.

## Atenção a custos

EKS, NAT Gateway, MSK Serverless, RDS, ElastiCache e tráfego geram cobrança contínua. Esta composição privilegia demonstração arquitetural, não o menor preço. Consulte a calculadora oficial, defina orçamento e alarmes e use uma conta isolada antes de executar `terraform apply`.

Para uma demonstração econômica, mantenha o Compose local ou reduza a topologia deliberadamente. Não aplique a infraestrutura completa apenas para produzir uma captura de tela.

O perfil `portfolio` mantém RDS e Redis em uma única zona para controlar custos.
Quando `ambiente = "producao"`, precondições do Terraform exigem RDS e Redis
Multi-AZ, RDS Proxy, Karpenter, classes não burstable, proteção contra exclusão,
backups e 365 dias de logs. Os testes em `tests/perfis.tftest.hcl` comprovam os
dois perfis sem acessar uma conta AWS.

O exemplo de produção reserva 200 GiB inicialmente e permite o autoscaling do
RDS até 2 TiB. O teto não gera alocação imediata, mas limita até onde o banco
pode crescer; monitore a tendência e planeje arquivamento ou separação física de
domínios antes de alcançá-lo. Mudanças de RDS em produção aguardam a janela de
manutenção em vez de usar aplicação imediata.

## Pré-requisitos

- AWS CLI autenticada na conta correta;
- Terraform compatível com `versoes.tf`;
- Docker com acesso ao ECR;
- `kubectl`, Helm e permissões para criar os recursos;
- domínio e certificado, caso o ingresso público seja habilitado.

O token de autenticação do Redis é uma variável sensível obrigatória. Gere-o fora do repositório:

```powershell
$env:TF_VAR_senha_redis = [Convert]::ToHexString(
  [Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
)

$credenciaisBanco = @{}
'checkout','estoque','risco','pagamento','razao','notificacao','registro' |
  ForEach-Object {
    $credenciaisBanco[$_] = [Convert]::ToHexString(
      [Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
    )
  }
$env:TF_VAR_credenciais_proxy_banco = $credenciaisBanco |
  ConvertTo-Json -Compress
```

Os valores ficam marcados como sensíveis pelo Terraform, mas ainda integram o
estado. Em produção, use backend remoto criptografado, versionado, com bloqueio
e acesso mínimo ao arquivo de estado. As sete credenciais alimentam o RDS Proxy
e, pelos mesmos Secrets Manager, os respectivos pods; não mantenha uma segunda
cópia manual dessas senhas.

## Validar sem criar recursos

```powershell
terraform -chdir=infra/terraform/aws fmt -check
terraform -chdir=infra/terraform/aws init -backend=false
terraform -chdir=infra/terraform/aws validate
terraform -chdir=infra/terraform/aws test
```

## Planejar

```powershell
Copy-Item infra\terraform\aws\exemplo.tfvars infra\terraform\aws\ambiente.tfvars
# A API do EKS permanece privada por padrao.
terraform -chdir=infra/terraform/aws init
terraform -chdir=infra/terraform/aws plan -var-file=ambiente.tfvars -out=plano.tfplan
```

Leia todo o plano. Verifique região, quantidade de nós, exposição da API, proteção contra exclusão e recursos com cobrança por hora.

Para produção, use `producao.tfvars.example` como referência. Não reduza as garantias apenas para fazer o plano passar; crie um ambiente com outro nome quando a intenção for uma bancada temporária.

Com o control plane privado, execute `kubectl` e Helm a partir de uma rede conectada
à VPC, como VPN corporativa, bastion restrito ou sessão administrativa via SSM.
Se uma demonstração exigir API pública, defina `api_kubernetes_publica = true` e
informe apenas CIDRs específicos; os blocos universais `0.0.0.0/0` e `::/0` são rejeitados.

Os workloads também não possuem saída HTTPS irrestrita. Em produção, defina
`cidrs_saida_https` com os CIDRs privados do proxy ou firewall de egress que
alcança OIDC, STS e integrações aprovadas. O Terraform rejeita produção sem esse
controle e nunca aceita `0.0.0.0/0` ou `::/0` nessa variável.

## Provisionar

```powershell
terraform -chdir=infra/terraform/aws apply plano.tfplan
terraform -chdir=infra/terraform/aws output
```

Depois, execute o comando `comando_kubeconfig` mostrado nas saídas.

Antes de implantar os pods, preencha o segredo de aplicação criado pelo
Terraform. A propriedade `redis-senha` deve receber exatamente o mesmo valor
usado em `TF_VAR_senha_redis`. As senhas PostgreSQL ficam nos sete segredos
criados para o RDS Proxy e não entram neste JSON:

```powershell
$chaveCriptografia = [Convert]::ToHexString(
  [Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
)
$novoSegredo = {
  [Convert]::ToHexString(
    [Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
  )
}
$segredoAplicacao = @{
  'redis-senha'                              = $env:TF_VAR_senha_redis
  'chave-criptografia-token'                 = $chaveCriptografia
  'chave-api-provedor'                       = & $novoSegredo
  'chave-api-provedor-principal'             = & $novoSegredo
  'segredo-webhook-provedor-principal'       = & $novoSegredo
  'chave-api-provedor-contingencia'          = & $novoSegredo
  'segredo-webhook-provedor-contingencia'    = & $novoSegredo
}
$segredoAplicacao = $segredoAplicacao | ConvertTo-Json -Compress
$arnSegredo = terraform -chdir=infra/terraform/aws output -raw aplicacao_segredo_arn
aws secretsmanager put-secret-value --secret-id $arnSegredo --secret-string $segredoAplicacao
```

Não grave o conteúdo dessas variáveis em `.tfvars`, scripts, logs ou histórico do Git.

## Construir e enviar imagens

1. execute `mvnw clean package`;
2. autentique o Docker no ECR;
3. construa uma imagem para cada serviço usando `docker/Dockerfile`;
4. envie a mesma tag imutável para todos os repositórios;
5. informe registro e tag no `values.yaml` do ambiente.

Não use `latest` em produção. Relacione a tag ao commit e preserve a rastreabilidade do artefato.

## Preparar o cluster

Instale antes da aplicação:

- AWS Load Balancer Controller, se houver ingresso;
- External Secrets Operator;
- métricas do cluster para HPA;
- Karpenter `1.14.1` e KEDA `2.20.2` nas versões fixadas pelo instalador;
- coletor OpenTelemetry e plataforma de observabilidade;
- Argo CD, caso use a aplicação declarada em `infra/kubernetes/argocd`.

O Karpenter executa no grupo gerenciado sob demanda e cria nós AL2023 fixados
em uma versão testada. As aplicações podem usar Spot com fallback sob demanda;
a fila SQS recebe avisos de interrupção para drenar os pods antes da remoção. O
NodePool limita CPU e memória para impedir escala de custo sem teto.

Depois de instalar External Secrets, Load Balancer Controller e
observabilidade, o comando abaixo instala Karpenter, KEDA e a aplicação usando
as saídas reais do Terraform:

```powershell
.\scripts\instalar-autoscalonamento-aws.ps1
```

O script fixa Karpenter e KEDA, informa fila de interrupções, função dos nós e
tag de descoberta, e espera os controladores ficarem prontos. Antes de promover
uma nova AMI ou versão de controlador, repita carga, interrupção e rollback em
homologação.

## Implantar com Helm

```powershell
helm lint infra/kubernetes/helm/orquestrapay
helm upgrade --install orquestrapay infra/kubernetes/helm/orquestrapay `
  --namespace orquestrapay --create-namespace `
  --values caminho\valores-producao.yaml `
  --wait --timeout 15m
```

Os valores de produção precisam usar as saídas reais do Terraform para ECR,
endpoint do RDS Proxy (`postgres_aplicacao_endpoint`), endpoint administrativo
direto (`postgres_endpoint`), Redis, MSK, Cognito, segredos PostgreSQL e funções
IAM. O host administrativo é usado somente pelo Job que cria bancos e usuários;
os serviços e o Apicurio usam o proxy. O arquivo versionado contém apenas
exemplos.

O chart mantém o autocadastro Avro desligado. Um Job com nome derivado do hash do
schema publica somente contratos ausentes e os init containers dos serviços
aguardam a versão exata. Assim, a primeira instalação e os upgrades não expõem
pods prontos antes da atualização do Apicurio. O Job concluído é removido
automaticamente depois de dez minutos.

Configure `global.seguranca.clientesId` com os clientes web e técnico,
`clienteMaquinaId` com o cliente de Client Credentials e
`empresaClienteMaquina` com o tenant fixo dessa integração. Use a saída
`waf_api_arn` em `ingresso.wafAclArn`. Nunca permita que o consumidor escolha a
empresa apenas enviando um claim ou cabeçalho.

Ao habilitar `ingresso.habilitado`, informe um domínio válido e
`ingresso.certificadoArn` com um certificado ACM. O chart aceita somente HTTPS no
ALB, exige o WAF e redireciona tentativas HTTP para a porta 443. As credenciais e
segredos HMAC dos provedores principal e contingência devem ser distintos no
Secrets Manager.

## Verificação pós-implantação

1. todos os pods prontos e distribuídos entre nós;
2. migrations concluídas em cada banco lógico;
3. Job `registrar-esquemas-*` concluído e 12 contratos registrados no Apicurio;
4. autenticação e isolamento entre empresas testados;
5. uma compra aprovada e uma compensada;
6. métricas, logs e traces chegando aos destinos;
7. KEDA reagindo a CPU e lag Kafka, Karpenter criando/removendo nós e PDBs respeitados;
8. pool e latência do RDS Proxy, backups e alarmes conferidos;
9. interrupção Spot simulada com backlog convergindo sem efeito financeiro duplicado.

## Destruir a bancada

Quando a infraestrutura for temporária, remova-a para interromper cobranças:

```powershell
terraform -chdir=infra/terraform/aws plan -destroy -var-file=ambiente.tfvars -out=destruir.tfplan
terraform -chdir=infra/terraform/aws apply destruir.tfplan
```

Recursos com proteção, retenção ou dependências externas podem exigir tratamento explícito. Confirme no console que não restaram NAT Gateway, balanceadores, bancos, clusters ou endereços cobrados.
