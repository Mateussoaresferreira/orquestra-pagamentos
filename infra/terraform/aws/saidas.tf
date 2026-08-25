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

output "postgres_aplicacao_endpoint" {
  description = "Endpoint do RDS Proxy quando habilitado; caso contrario, endpoint direto do PostgreSQL."
  value       = var.habilitar_proxy_banco ? aws_db_proxy.principal[0].endpoint : aws_db_instance.principal.address
}

output "postgres_segredos_usuarios_arns" {
  description = "ARNs das credenciais por usuario usadas simultaneamente pelo RDS Proxy e pelos workloads."
  value       = { for nome, segredo in aws_secretsmanager_secret.proxy_banco : nome => segredo.arn }
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

output "waf_api_arn" {
  description = "ARN a informar em ingresso.wafAclArn no chart Helm."
  value       = try(aws_wafv2_web_acl.api[0].arn, null)
}
output "funcao_iam_keda" {
  description = "Funcao IAM para o service account keda-operator consultar o lag do MSK."
  value       = aws_iam_role.keda.arn
}

output "karpenter_funcao_nos" {
  description = "Nome da funcao IAM usada pelas instancias dinamicas do Karpenter."
  value       = var.habilitar_karpenter ? module.karpenter.node_iam_role_name : null
}

output "karpenter_fila_interrupcoes" {
  description = "Fila SQS consumida pelo Karpenter para drenar nos Spot antes da interrupcao."
  value       = var.habilitar_karpenter ? module.karpenter.queue_name : null
}

output "karpenter_identificador_descoberta" {
  description = "Tag usada pelo EC2NodeClass para descobrir sub-redes e grupo de seguranca."
  value       = local.prefixo
}
