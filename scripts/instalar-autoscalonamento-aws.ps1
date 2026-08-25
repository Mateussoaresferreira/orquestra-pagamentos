param(
    [string]$DiretorioTerraform = (Join-Path $PSScriptRoot '..\infra\terraform\aws'),
    [string]$VersaoKarpenter = '1.14.1',
    [string]$VersaoKeda = '2.20.2',
    [string]$AliasAmi = 'al2023@v20260610'
)

$ErrorActionPreference = 'Stop'

function Obter-SaidaTerraform {
    param([Parameter(Mandatory)][string]$Nome)

    $valor = terraform -chdir=$DiretorioTerraform output -raw $Nome
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($valor)) {
        throw "Nao foi possivel obter a saida Terraform '$Nome'."
    }

    return $valor.Trim()
}

$cluster = Obter-SaidaTerraform 'cluster_eks'
$filaInterrupcoes = Obter-SaidaTerraform 'karpenter_fila_interrupcoes'
$funcaoNos = Obter-SaidaTerraform 'karpenter_funcao_nos'
$identificadorDescoberta = Obter-SaidaTerraform 'karpenter_identificador_descoberta'
$funcaoKeda = Obter-SaidaTerraform 'funcao_iam_keda'

helm upgrade --install karpenter oci://public.ecr.aws/karpenter/karpenter `
    --version $VersaoKarpenter `
    --namespace karpenter `
    --create-namespace `
    --values (Join-Path $PSScriptRoot '..\infra\kubernetes\valores-karpenter.yaml') `
    --set "settings.clusterName=$cluster" `
    --set "settings.interruptionQueue=$filaInterrupcoes" `
    --wait `
    --timeout 10m
if ($LASTEXITCODE -ne 0) { throw 'Falha ao instalar o Karpenter.' }

helm repo add kedacore https://kedacore.github.io/charts --force-update
helm repo update kedacore
helm upgrade --install keda kedacore/keda `
    --version $VersaoKeda `
    --namespace keda `
    --create-namespace `
    --set 'serviceAccount.operator.name=keda-operator' `
    --set-string "serviceAccount.operator.annotations.eks\.amazonaws\.com/role-arn=$funcaoKeda" `
    --wait `
    --timeout 10m
if ($LASTEXITCODE -ne 0) { throw 'Falha ao instalar o KEDA.' }

helm upgrade --install orquestrapay (Join-Path $PSScriptRoot '..\infra\kubernetes\helm\orquestrapay') `
    --namespace orquestrapay `
    --create-namespace `
    --set "karpenter.funcaoNos=$funcaoNos" `
    --set "karpenter.identificadorDescoberta=$identificadorDescoberta" `
    --set-string "karpenter.aliasAmi=$AliasAmi" `
    --wait `
    --timeout 15m
if ($LASTEXITCODE -ne 0) { throw 'Falha ao instalar a Orquestra de Pagamentos.' }

kubectl wait --for=condition=Ready nodepool/orquestrapay --timeout=5m
if ($LASTEXITCODE -ne 0) { throw 'O NodePool do Karpenter nao ficou pronto.' }

Write-Host 'Karpenter, KEDA e Orquestra de Pagamentos instalados e prontos.'
