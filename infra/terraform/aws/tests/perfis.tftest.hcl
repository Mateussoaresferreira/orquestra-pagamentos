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
    aws_elasticache_replication_group.principal,
    aws_cloudwatch_log_group.aplicacao,
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

}
