data "aws_iam_policy_document" "assumir_eks" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["eks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "cluster" {
  name               = "${local.prefixo}-eks-cluster"
  assume_role_policy = data.aws_iam_policy_document.assumir_eks.json
}

resource "aws_iam_role_policy_attachment" "cluster" {
  role       = aws_iam_role.cluster.name
  policy_arn = "arn:${data.aws_partition.atual.partition}:iam::aws:policy/AmazonEKSClusterPolicy"
}

resource "aws_kms_key" "segredos_eks" {
  description             = "Criptografia dos Secrets do EKS ${local.prefixo}"
  enable_key_rotation     = true
  deletion_window_in_days = var.proteger_exclusao ? 30 : 7
}

resource "aws_kms_alias" "segredos_eks" {
  name          = "alias/${local.prefixo}-segredos-eks"
  target_key_id = aws_kms_key.segredos_eks.key_id
}

resource "aws_eks_cluster" "principal" {
  name                = local.prefixo
  role_arn            = aws_iam_role.cluster.arn
  version             = var.versao_kubernetes
  deletion_protection = var.proteger_exclusao

  vpc_config {
    subnet_ids              = values(aws_subnet.privada)[*].id
    security_group_ids      = [aws_security_group.aplicacao.id]
    endpoint_private_access = true
    endpoint_public_access  = var.api_kubernetes_publica
    public_access_cidrs     = var.api_kubernetes_publica ? var.cidrs_api_kubernetes : []
  }

  access_config {
    authentication_mode = "API"
  }

  encryption_config {
    provider {
      key_arn = aws_kms_key.segredos_eks.arn
    }
    resources = ["secrets"]
  }

  lifecycle {
    precondition {
      condition     = !var.api_kubernetes_publica || length(var.cidrs_api_kubernetes) > 0
      error_message = "Informe ao menos um CIDR restrito ao habilitar a API publica do EKS."
    }
  }

  depends_on = [aws_iam_role_policy_attachment.cluster]
}

data "aws_iam_policy_document" "assumir_nos" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "nos" {
  name               = "${local.prefixo}-eks-nos"
  assume_role_policy = data.aws_iam_policy_document.assumir_nos.json
}

resource "aws_iam_role_policy_attachment" "nos" {
  for_each = toset([
    "AmazonEKSWorkerNodePolicy",
    "AmazonEC2ContainerRegistryReadOnly",
    "AmazonEKS_CNI_Policy"
  ])
  role       = aws_iam_role.nos.name
  policy_arn = "arn:${data.aws_partition.atual.partition}:iam::aws:policy/${each.value}"
}

resource "aws_eks_node_group" "principal" {
  cluster_name    = aws_eks_cluster.principal.name
  node_group_name = "aplicacoes"
  node_role_arn   = aws_iam_role.nos.arn
  subnet_ids      = values(aws_subnet.privada)[*].id
  instance_types  = [var.tipo_instancia_nos]
  capacity_type   = "ON_DEMAND"

  scaling_config {
    desired_size = var.nos_desejados
    min_size     = var.nos_minimos
    max_size     = var.nos_maximos
  }

  update_config { max_unavailable = 1 }

  lifecycle {
    precondition {
      condition     = var.nos_minimos <= var.nos_desejados && var.nos_desejados <= var.nos_maximos
      error_message = "A quantidade de nos deve respeitar minimo <= desejado <= maximo."
    }
    precondition {
      condition     = lower(var.ambiente) != "producao" || !can(regex("^t[0-9]", lower(var.tipo_instancia_nos)))
      error_message = "Producao nao deve usar instancias EKS burstable da familia T."
    }
    precondition {
      condition     = lower(var.ambiente) != "producao" || var.habilitar_karpenter
      error_message = "Producao exige Karpenter para adicionar capacidade quando o KEDA criar novos pods."
    }
  }

  depends_on = [aws_iam_role_policy_attachment.nos]
}

resource "aws_eks_addon" "essenciais" {
  for_each = toset(concat(
    ["vpc-cni", "coredns", "kube-proxy", "metrics-server"],
    var.habilitar_karpenter ? ["eks-pod-identity-agent"] : []
  ))
  cluster_name = aws_eks_cluster.principal.name
  addon_name   = each.value
  depends_on   = [aws_eks_node_group.principal]
}

data "tls_certificate" "oidc" {
  url = aws_eks_cluster.principal.identity[0].oidc[0].issuer
}

resource "aws_iam_openid_connect_provider" "eks" {
  url             = aws_eks_cluster.principal.identity[0].oidc[0].issuer
  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = [data.tls_certificate.oidc.certificates[0].sha1_fingerprint]
}

data "aws_iam_policy_document" "assumir_aplicacao" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    condition {
      test     = "StringEquals"
      variable = "${replace(aws_iam_openid_connect_provider.eks.url, "https://", "")}:sub"
      values   = ["system:serviceaccount:orquestrapay:orquestrapay"]
    }
    condition {
      test     = "StringEquals"
      variable = "${replace(aws_iam_openid_connect_provider.eks.url, "https://", "")}:aud"
      values   = ["sts.amazonaws.com"]
    }
    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.eks.arn]
    }
  }
}

resource "aws_iam_role" "aplicacao" {
  name               = "${local.prefixo}-aplicacao"
  assume_role_policy = data.aws_iam_policy_document.assumir_aplicacao.json
}

resource "aws_iam_role_policy" "aplicacao" {
  name = "acesso-minimo"
  role = aws_iam_role.aplicacao.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "kafka-cluster:Connect",
          "kafka-cluster:DescribeCluster",
          "kafka-cluster:ReadData",
          "kafka-cluster:WriteData",
          "kafka-cluster:CreateTopic",
          "kafka-cluster:DescribeTopic",
          "kafka-cluster:AlterGroup",
          "kafka-cluster:DescribeGroup"
        ]
        Resource = [
          aws_msk_serverless_cluster.principal.arn,
          "${replace(aws_msk_serverless_cluster.principal.arn, ":cluster/", ":topic/")}/*",
          "${replace(aws_msk_serverless_cluster.principal.arn, ":cluster/", ":group/")}/*"
        ]
      }
    ]
  })
}

data "aws_iam_policy_document" "assumir_segredos" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    condition {
      test     = "StringEquals"
      variable = "${replace(aws_iam_openid_connect_provider.eks.url, "https://", "")}:sub"
      values   = ["system:serviceaccount:orquestrapay:orquestrapay-segredos"]
    }
    condition {
      test     = "StringEquals"
      variable = "${replace(aws_iam_openid_connect_provider.eks.url, "https://", "")}:aud"
      values   = ["sts.amazonaws.com"]
    }
    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.eks.arn]
    }
  }
}

resource "aws_iam_role" "segredos" {
  name               = "${local.prefixo}-segredos"
  assume_role_policy = data.aws_iam_policy_document.assumir_segredos.json
}

resource "aws_iam_role_policy" "segredos" {
  name = "leitura-segredos-aplicacao"
  role = aws_iam_role.segredos.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = ["secretsmanager:GetSecretValue"]
      Resource = concat(
        [
          aws_db_instance.principal.master_user_secret[0].secret_arn,
          aws_secretsmanager_secret.aplicacao.arn,
        ],
        [for segredo in values(aws_secretsmanager_secret.proxy_banco) : segredo.arn]
      )
    }]
  })
}

resource "aws_ecr_repository" "servicos" {
  for_each             = local.repositorios
  name                 = each.value
  image_tag_mutability = "IMMUTABLE"
  force_delete         = !var.proteger_exclusao

  image_scanning_configuration { scan_on_push = true }
  encryption_configuration { encryption_type = "AES256" }
}

resource "aws_ecr_lifecycle_policy" "servicos" {
  for_each   = aws_ecr_repository.servicos
  repository = each.value.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Manter as ultimas 20 imagens"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = 20
      }
      action = { type = "expire" }
    }]
  })
}
