locals {
  usuarios_proxy_banco = {
    checkout    = "checkout_app"
    estoque     = "estoque_app"
    risco       = "risco_app"
    pagamento   = "pagamento_app"
    razao       = "razao_app"
    notificacao = "notificacao_app"
    registro    = "registro_app"
  }

  credenciais_proxy_completas = (
    toset(keys(var.credenciais_proxy_banco)) == toset(keys(local.usuarios_proxy_banco)) &&
    alltrue([
      for chave in keys(local.usuarios_proxy_banco) : length(try(var.credenciais_proxy_banco[chave], "")) >= 24
    ])
  )
}

resource "aws_secretsmanager_secret" "proxy_banco" {
  for_each = var.habilitar_proxy_banco ? local.usuarios_proxy_banco : {}

  name                    = "${local.prefixo}/banco/${each.key}"
  description             = "Credencial PostgreSQL do usuario ${each.value} usada pelo RDS Proxy"
  recovery_window_in_days = var.proteger_exclusao ? 30 : 0
}

resource "aws_secretsmanager_secret_version" "proxy_banco" {
  for_each = aws_secretsmanager_secret.proxy_banco

  secret_id = each.value.id
  secret_string = jsonencode({
    username = local.usuarios_proxy_banco[each.key]
    password = try(var.credenciais_proxy_banco[each.key], "")
  })
}

data "aws_iam_policy_document" "assumir_proxy_banco" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["rds.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "proxy_banco" {
  count = var.habilitar_proxy_banco ? 1 : 0

  name               = "${local.prefixo}-proxy-banco"
  assume_role_policy = data.aws_iam_policy_document.assumir_proxy_banco.json
}

resource "aws_iam_role_policy" "proxy_banco" {
  count = var.habilitar_proxy_banco ? 1 : 0

  name = "ler-credenciais-postgresql"
  role = aws_iam_role.proxy_banco[0].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["secretsmanager:GetSecretValue"]
      Resource = [for segredo in values(aws_secretsmanager_secret.proxy_banco) : segredo.arn]
    }]
  })
}

resource "aws_security_group" "proxy_banco" {
  count = var.habilitar_proxy_banco ? 1 : 0

  name        = "${local.prefixo}-proxy-banco"
  description = "Acesso dos workloads ao RDS Proxy"
  vpc_id      = aws_vpc.principal.id

  ingress {
    description     = "PostgreSQL recebido dos workloads"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.aplicacao.id]
  }

  egress {
    description = "PostgreSQL privado no RDS"
    from_port   = 5432
    to_port     = 5432
    protocol    = "tcp"
    cidr_blocks = [var.cidr_vpc]
  }
}

resource "aws_db_proxy" "principal" {
  count = var.habilitar_proxy_banco ? 1 : 0

  name                   = "${local.prefixo}-postgres"
  engine_family          = "POSTGRESQL"
  idle_client_timeout    = 1800
  require_tls            = true
  role_arn               = aws_iam_role.proxy_banco[0].arn
  vpc_security_group_ids = [aws_security_group.proxy_banco[0].id]
  vpc_subnet_ids         = values(aws_subnet.privada)[*].id

  dynamic "auth" {
    for_each = aws_secretsmanager_secret.proxy_banco
    content {
      auth_scheme               = "SECRETS"
      client_password_auth_type = "POSTGRES_SCRAM_SHA_256"
      description               = "Usuario ${local.usuarios_proxy_banco[auth.key]}"
      iam_auth                  = "DISABLED"
      secret_arn                = auth.value.arn
    }
  }

  lifecycle {
    precondition {
      condition     = local.credenciais_proxy_completas
      error_message = "Habilitar o RDS Proxy exige exatamente as credenciais checkout, estoque, risco, pagamento, razao, notificacao e registro, todas com ao menos 24 caracteres."
    }
  }

  depends_on = [aws_secretsmanager_secret_version.proxy_banco]
}

resource "aws_db_proxy_default_target_group" "principal" {
  count = var.habilitar_proxy_banco ? 1 : 0

  db_proxy_name = aws_db_proxy.principal[0].name

  connection_pool_config {
    connection_borrow_timeout    = var.tempo_emprestimo_proxy_segundos
    max_connections_percent      = var.percentual_conexoes_proxy
    max_idle_connections_percent = var.percentual_conexoes_ociosas_proxy
  }
}

resource "aws_db_proxy_target" "principal" {
  count = var.habilitar_proxy_banco ? 1 : 0

  db_instance_identifier = aws_db_instance.principal.identifier
  db_proxy_name          = aws_db_proxy.principal[0].name
  target_group_name      = aws_db_proxy_default_target_group.principal[0].name
}
