data "aws_iam_policy_document" "assumir_keda" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    condition {
      test     = "StringEquals"
      variable = "${replace(aws_iam_openid_connect_provider.eks.url, "https://", "")}:sub"
      values   = ["system:serviceaccount:keda:keda-operator"]
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

resource "aws_iam_role" "keda" {
  name               = "${local.prefixo}-keda"
  assume_role_policy = data.aws_iam_policy_document.assumir_keda.json
}

resource "aws_iam_role_policy" "keda_msk" {
  name = "consultar-lag-msk"
  role = aws_iam_role.keda.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = [
        "kafka-cluster:Connect",
        "kafka-cluster:DescribeCluster",
        "kafka-cluster:DescribeTopic",
        "kafka-cluster:DescribeGroup",
        "kafka-cluster:ReadData"
      ]
      Resource = [
        aws_msk_serverless_cluster.principal.arn,
        "${replace(aws_msk_serverless_cluster.principal.arn, ":cluster/", ":topic/")}/*",
        "${replace(aws_msk_serverless_cluster.principal.arn, ":cluster/", ":group/")}/*"
      ]
    }]
  })
}
