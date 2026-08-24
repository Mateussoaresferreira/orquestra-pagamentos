resource "aws_msk_serverless_cluster" "principal" {
  cluster_name = local.prefixo

  vpc_config {
    subnet_ids         = values(aws_subnet.privada)[*].id
    security_group_ids = [aws_security_group.dados.id]
  }

  client_authentication {
    sasl {
      iam { enabled = true }
    }
  }
}
