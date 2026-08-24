# Implantação de referência na AWS

## Objetivo

A infraestrutura em `infra/terraform/aws` representa uma topologia de produção para estudo e portfólio. Ela não é criada durante os testes locais e não deve ser aplicada sem revisar custos, limites e segurança da conta.

## Recursos modelados

- VPC com sub-redes públicas e privadas em três zonas;
- NAT Gateway para saída dos workloads privados;
- EKS com grupo de nós gerenciado, OIDC, IRSA e Secrets criptografados por KMS;
- ECR para as sete imagens;
- RDS PostgreSQL criptografado;
- ElastiCache Redis com criptografia, TLS, autenticação e failover configurável;
- MSK Serverless com autenticação IAM;
- Cognito com escopos, grupos e clientes para PKCE e máquina a máquina;
- Secrets Manager para banco, Redis, criptografia e integração com o provedor;
- SNS criptografado por chave KMS própria e alarmes CloudWatch.

## Atenção a custos

EKS, NAT Gateway, MSK Serverless, RDS, ElastiCache e tráfego geram cobrança contínua. Esta composição privilegia demonstração arquitetural, não o menor preço. Consulte a calculadora oficial, defina orçamento e alarmes e use uma conta isolada antes de executar `terraform apply`.

Para uma demonstração econômica, mantenha o Compose local ou reduza a topologia deliberadamente. Não aplique a infraestrutura completa apenas para produzir uma captura de tela.

O perfil `portfolio` mantém RDS e Redis em uma única zona para controlar custos. Quando `ambiente = "producao"`, precondições do Terraform exigem RDS e Redis Multi-AZ, proteção contra exclusão, backups e 365 dias de logs. Os testes em `tests/perfis.tftest.hcl` comprovam os dois perfis sem acessar uma conta AWS.

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
```

O valor fica marcado como sensível pelo Terraform, mas ainda integra o estado. Em produção, use backend remoto criptografado, versionado, com bloqueio e acesso mínimo ao arquivo de estado.

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

Antes de implantar os pods, preencha o segredo de aplicação criado pelo Terraform. A propriedade `redis-senha` deve receber exatamente o mesmo valor usado em `TF_VAR_senha_redis`:

```powershell
$chaveCriptografia = [Convert]::ToHexString(
  [Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
)
$chaveProvedor = [Convert]::ToHexString(
  [Security.Cryptography.RandomNumberGenerator]::GetBytes(24)
)
$senhaBanco = @{}
'checkout','estoque','risco','pagamento','razao','notificacao','registro' | ForEach-Object {
  $senhaBanco["banco-$($_)-senha"] = [Convert]::ToHexString(
    [Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
  )
}
$segredoAplicacao = @{
  'redis-senha'              = $env:TF_VAR_senha_redis
  'chave-criptografia-token' = $chaveCriptografia
  'chave-api-provedor'       = $chaveProvedor
}
$senhaBanco.GetEnumerator() | ForEach-Object { $segredoAplicacao[$_.Key] = $_.Value }
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
- coletor OpenTelemetry e plataforma de observabilidade;
- Argo CD, caso use a aplicação declarada em `infra/kubernetes/argocd`.

## Implantar com Helm

```powershell
helm lint infra/kubernetes/helm/orquestrapay
helm upgrade --install orquestrapay infra/kubernetes/helm/orquestrapay `
  --namespace orquestrapay --create-namespace `
  --values caminho\valores-producao.yaml `
  --wait --timeout 15m
```

Os valores de produção precisam usar as saídas reais do Terraform para ECR, RDS,
Redis, MSK, Cognito e as duas funções IAM (`funcao_iam_aplicacao` e
`funcao_iam_segredos`). A primeira autoriza somente o MSK; a segunda é usada
exclusivamente pelo External Secrets. O arquivo versionado contém apenas exemplos.

Ao habilitar `ingresso.habilitado`, informe um domínio válido e
`ingresso.certificadoArn` com um certificado ACM. O chart aceita somente HTTPS no
ALB e redireciona tentativas HTTP para a porta 443.

## Verificação pós-implantação

1. todos os pods prontos e distribuídos entre nós;
2. migrations concluídas em cada banco lógico;
3. schemas registrados no Apicurio;
4. autenticação e isolamento entre empresas testados;
5. uma compra aprovada e uma compensada;
6. métricas, logs e traces chegando aos destinos;
7. HPA, PDB, backups e alarmes conferidos.

## Destruir a bancada

Quando a infraestrutura for temporária, remova-a para interromper cobranças:

```powershell
terraform -chdir=infra/terraform/aws plan -destroy -var-file=ambiente.tfvars -out=destruir.tfplan
terraform -chdir=infra/terraform/aws apply destruir.tfplan
```

Recursos com proteção, retenção ou dependências externas podem exigir tratamento explícito. Confirme no console que não restaram NAT Gateway, balanceadores, bancos, clusters ou endereços cobrados.
