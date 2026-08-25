resource "aws_db_subnet_group" "principal" {
  name       = local.prefixo
  subnet_ids = values(aws_subnet.privada)[*].id
}

resource "aws_db_instance" "principal" {
  identifier                      = "${local.prefixo}-postgres"
  engine                          = "postgres"
  instance_class                  = var.classe_banco
  allocated_storage               = 50
  max_allocated_storage           = 200
  storage_type                    = "gp3"
  storage_encrypted               = true
  username                        = "orquestrapay"
  manage_master_user_password     = true
  db_subnet_group_name            = aws_db_subnet_group.principal.name
  vpc_security_group_ids          = [aws_security_group.dados.id]
  publicly_accessible             = false
  multi_az                        = var.banco_multi_az
  backup_retention_period         = var.retencao_backup_banco_dias
  auto_minor_version_upgrade      = true
  performance_insights_enabled    = true
  enabled_cloudwatch_logs_exports = ["postgresql", "upgrade"]
  deletion_protection             = var.proteger_exclusao
  skip_final_snapshot             = !var.proteger_exclusao
  final_snapshot_identifier       = var.proteger_exclusao ? "${local.prefixo}-final" : null
  apply_immediately               = true

  lifecycle {
    precondition {
      condition = lower(var.ambiente) != "producao" || (
        var.banco_multi_az &&
        var.proteger_exclusao &&
        var.retencao_backup_banco_dias >= 7 &&
        var.habilitar_proxy_banco
      )
      error_message = "Producao exige RDS Multi-AZ, RDS Proxy, protecao contra exclusao e ao menos 7 dias de backup."
    }
    precondition {
      condition     = lower(var.ambiente) != "producao" || !startswith(lower(var.classe_banco), "db.t")
      error_message = "Producao nao deve usar classe RDS burstable da familia T."
    }
  }
}

resource "aws_secretsmanager_secret" "aplicacao" {
  name                    = "${local.prefixo}/aplicacao"
  description             = "Segredos criptograficos da aplicacao; preencha antes da implantacao"
  recovery_window_in_days = var.proteger_exclusao ? 30 : 0
}

resource "aws_elasticache_subnet_group" "principal" {
  name       = local.prefixo
  subnet_ids = values(aws_subnet.privada)[*].id
}

resource "aws_elasticache_replication_group" "principal" {
  replication_group_id       = "${local.prefixo}-redis"
  description                = "Idempotencia e limite de requisicoes da Orquestra de Pagamentos"
  engine                     = "redis"
  engine_version             = "7.1"
  node_type                  = var.classe_redis
  num_cache_clusters         = var.redis_multi_az ? 2 : 1
  port                       = 6379
  subnet_group_name          = aws_elasticache_subnet_group.principal.name
  security_group_ids         = [aws_security_group.dados.id]
  at_rest_encryption_enabled = true
  transit_encryption_enabled = true
  transit_encryption_mode    = "required"
  auth_token                 = var.senha_redis
  auth_token_update_strategy = "SET"
  automatic_failover_enabled = var.redis_multi_az
  multi_az_enabled           = var.redis_multi_az
  snapshot_retention_limit   = var.retencao_snapshot_redis_dias
  apply_immediately          = true

  lifecycle {
    precondition {
      condition = lower(var.ambiente) != "producao" || (
        var.redis_multi_az &&
        var.retencao_snapshot_redis_dias >= 7
      )
      error_message = "Producao exige Redis Multi-AZ com failover e ao menos 7 dias de snapshots."
    }
    precondition {
      condition     = lower(var.ambiente) != "producao" || !startswith(lower(var.classe_redis), "cache.t")
      error_message = "Producao nao deve usar classe Redis burstable da familia T."
    }
  }
}
