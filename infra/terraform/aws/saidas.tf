output "cluster_eks" {
  value = aws_eks_cluster.principal.name
}

output "comando_kubeconfig" {
  value = "aws eks update-kubeconfig --region ${var.regiao} --name ${aws_eks_cluster.principal.name}"
}

output "registro_ecr" {
  value = "${data.aws_caller_identity.atual.account_id}.dkr.ecr.${var.regiao}.amazonaws.com"
}

output "repositorios_ecr" {
  value = { for nome, repositorio in aws_ecr_repository.servicos : nome => repositorio.repository_url }
}

output "postgres_endpoint" {
  value = aws_db_instance.principal.address
}

output "postgres_segredo_arn" {
  value     = aws_db_instance.principal.master_user_secret[0].secret_arn
  sensitive = true
}

output "aplicacao_segredo_arn" {
  value = aws_secretsmanager_secret.aplicacao.arn
}

output "redis_endpoint" {
  value = aws_elasticache_replication_group.principal.primary_endpoint_address
}

output "msk_servidores" {
  value = aws_msk_serverless_cluster.principal.bootstrap_brokers_sasl_iam
}

output "funcao_iam_aplicacao" {
  value = aws_iam_role.aplicacao.arn
}

output "funcao_iam_segredos" {
  value = aws_iam_role.segredos.arn
}

output "cognito_emissor" {
  value = "https://cognito-idp.${var.regiao}.amazonaws.com/${aws_cognito_user_pool.principal.id}"
}

output "cognito_cliente_web" {
  value = aws_cognito_user_pool_client.web.id
}

output "cognito_cliente_maquina" {
  value = aws_cognito_user_pool_client.maquina.id
}

output "cognito_dominio" {
  value = "https://${aws_cognito_user_pool_domain.principal.domain}.auth.${var.regiao}.amazoncognito.com"
}
