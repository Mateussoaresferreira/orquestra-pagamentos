mock_provider "aws" {
  mock_data "aws_availability_zones" {
    defaults = {
      names = ["us-east-1a", "us-east-1b", "us-east-1c"]
    }
  }

  mock_data "aws_caller_identity" {
    defaults = {
      account_id = "123456789012"
    }
  }

  mock_data "aws_partition" {
    defaults = {
      partition = "aws"
    }
  }

  mock_data "aws_iam_policy_document" {
    defaults = {
      json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}"
    }
  }
}

mock_provider "archive" {}
mock_provider "tls" {}

run "portfolio_economico_valido" {
  command = plan

  variables {
    senha_redis = "senha-redis-portfolio-1234567890"
  }

  assert {
    condition     = !aws_db_instance.principal.multi_az
    error_message = "O perfil de portfolio deveria manter RDS de uma zona para controlar custos."
  }

  assert {
    condition     = !aws_elasticache_replication_group.principal.multi_az_enabled
    error_message = "O perfil de portfolio deveria manter Redis de um no para controlar custos."
  }

  assert {
    condition = alltrue([
      contains(keys(local.descricoes_escopos), "webhooks:ler"),
      contains(keys(local.descricoes_escopos), "webhooks:gerenciar"),
      contains(keys(local.descricoes_escopos), "razao:escrever")
    ])
    error_message = "A infraestrutura deve declarar todos os escopos exigidos pela API."
  }

  assert {
    condition     = !contains(aws_cognito_user_pool_client.web.write_attributes, "custom:empresa_id")
    error_message = "O cliente web nao pode permitir que o usuario altere sua empresa."
  }

  assert {
    condition = toset(aws_cognito_user_pool_client.web.allowed_oauth_scopes) == toset([
      "openid", "email", "profile"
    ])
    error_message = "O cliente web deve usar papeis, sem receber escopos privilegiados da API."
  }
}

run "producao_insegura_rejeitada" {
  command = plan

  variables {
    ambiente          = "producao"
    senha_redis       = "senha-redis-producao-1234567890"
    cidrs_saida_https = ["10.42.240.0/24"]
  }

  expect_failures = [
    aws_db_instance.principal,
    aws_eks_node_group.principal,
    aws_elasticache_replication_group.principal,
    aws_cloudwatch_log_group.aplicacao,
    aws_wafv2_web_acl.api[0],
  ]
}

run "producao_sem_saida_controlada_rejeitada" {
  command = plan

  variables {
    ambiente                     = "producao"
    senha_redis                  = "senha-redis-producao-1234567890"
    banco_multi_az               = true
    retencao_backup_banco_dias   = 35
    redis_multi_az               = true
    retencao_snapshot_redis_dias = 7
    proteger_exclusao            = true
    retencao_logs_dias           = 365
    habilitar_waf                = true
    habilitar_proxy_banco        = true
    habilitar_karpenter          = true
    tipo_instancia_nos           = "m7i.large"
    classe_banco                 = "db.r7g.large"
    classe_redis                 = "cache.r7g.large"
    credenciais_proxy_banco = {
      checkout    = "senha-checkout-producao-1234567890"
      estoque     = "senha-estoque-producao-1234567890"
      risco       = "senha-risco-producao-1234567890"
      pagamento   = "senha-pagamento-producao-1234567890"
      razao       = "senha-razao-producao-1234567890"
      notificacao = "senha-notificacao-producao-1234567890"
      registro    = "senha-registro-producao-1234567890"
    }
  }

  expect_failures = [aws_security_group.aplicacao]
}

run "producao_resiliente_valida" {
  command = plan

  variables {
    ambiente                     = "producao"
    senha_redis                  = "senha-redis-producao-1234567890"
    banco_multi_az               = true
    retencao_backup_banco_dias   = 35
    redis_multi_az               = true
    retencao_snapshot_redis_dias = 7
    proteger_exclusao            = true
    retencao_logs_dias           = 365
    cidrs_saida_https            = ["10.42.240.0/24"]
    habilitar_waf                = true
    habilitar_proxy_banco        = true
    habilitar_karpenter          = true
    tipo_instancia_nos           = "m7i.large"
    nos_maximos                  = 20
    classe_banco                 = "db.r7g.large"
    classe_redis                 = "cache.r7g.large"
    credenciais_proxy_banco = {
      checkout    = "senha-checkout-producao-1234567890"
      estoque     = "senha-estoque-producao-1234567890"
      risco       = "senha-risco-producao-1234567890"
      pagamento   = "senha-pagamento-producao-1234567890"
      razao       = "senha-razao-producao-1234567890"
      notificacao = "senha-notificacao-producao-1234567890"
      registro    = "senha-registro-producao-1234567890"
    }
  }

  assert {
    condition     = aws_db_instance.principal.multi_az
    error_message = "O RDS de producao deve estar em mais de uma zona."
  }

  assert {
    condition     = aws_elasticache_replication_group.principal.automatic_failover_enabled
    error_message = "O Redis de producao deve possuir failover automatico."
  }

  assert {
    condition     = aws_eks_cluster.principal.deletion_protection
    error_message = "O EKS de producao deve estar protegido contra exclusao acidental."
  }

  assert {
    condition     = aws_wafv2_web_acl.api[0].scope == "REGIONAL"
    error_message = "A API publica de producao deve possuir WAF regional."
  }

  assert {
    condition     = aws_db_proxy.principal[0].require_tls
    error_message = "O RDS Proxy de producao deve exigir TLS."
  }

  assert {
    condition     = module.karpenter.queue_name != null
    error_message = "O Karpenter de producao deve possuir fila de interrupcoes."
  }

  assert {
    condition     = aws_cloudwatch_metric_alarm.conexoes_proxy_banco[0].threshold == 70
    error_message = "A saturacao do pool do RDS Proxy deve gerar alerta antes do esgotamento."
  }

}
