data "archive_file" "gatilho_token" {
  type        = "zip"
  source_file = "${path.module}/lambda/incluir_empresa.py"
  output_path = "${path.module}/lambda/incluir_empresa.zip"
}

data "aws_iam_policy_document" "assumir_lambda" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "gatilho_token" {
  name               = "${local.prefixo}-gatilho-token"
  assume_role_policy = data.aws_iam_policy_document.assumir_lambda.json
}

resource "aws_iam_role_policy_attachment" "gatilho_token" {
  role       = aws_iam_role.gatilho_token.name
  policy_arn = "arn:${data.aws_partition.atual.partition}:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

resource "aws_iam_role_policy_attachment" "gatilho_token_xray" {
  role       = aws_iam_role.gatilho_token.name
  policy_arn = "arn:${data.aws_partition.atual.partition}:iam::aws:policy/AWSXRayDaemonWriteAccess"
}

resource "aws_lambda_function" "incluir_empresa" {
  function_name    = "${local.prefixo}-incluir-empresa-token"
  role             = aws_iam_role.gatilho_token.arn
  runtime          = "python3.13"
  handler          = "incluir_empresa.manipular"
  filename         = data.archive_file.gatilho_token.output_path
  source_code_hash = data.archive_file.gatilho_token.output_base64sha256
  timeout          = 5
  memory_size      = 128

  tracing_config {
    mode = "Active"
  }

  depends_on = [
    aws_iam_role_policy_attachment.gatilho_token,
    aws_iam_role_policy_attachment.gatilho_token_xray
  ]
}

resource "aws_lambda_permission" "cognito" {
  statement_id   = "PermitirCognito"
  action         = "lambda:InvokeFunction"
  function_name  = aws_lambda_function.incluir_empresa.function_name
  principal      = "cognito-idp.amazonaws.com"
  source_account = data.aws_caller_identity.atual.account_id
  source_arn     = "arn:${data.aws_partition.atual.partition}:cognito-idp:${var.regiao}:${data.aws_caller_identity.atual.account_id}:userpool/*"
}

resource "aws_cognito_user_pool" "principal" {
  name                     = local.prefixo
  deletion_protection      = var.proteger_exclusao ? "ACTIVE" : "INACTIVE"
  username_attributes      = ["email"]
  auto_verified_attributes = ["email"]

  password_policy {
    minimum_length                   = 14
    require_lowercase                = true
    require_numbers                  = true
    require_symbols                  = true
    require_uppercase                = true
    temporary_password_validity_days = 2
  }

  schema {
    attribute_data_type      = "String"
    developer_only_attribute = false
    mutable                  = true
    name                     = "empresa_id"
    required                 = false
    string_attribute_constraints {
      min_length = 36
      max_length = 36
    }
  }

  lambda_config {
    pre_token_generation_config {
      lambda_arn     = aws_lambda_function.incluir_empresa.arn
      lambda_version = "V2_0"
    }
  }

  depends_on = [aws_lambda_permission.cognito]
}

resource "aws_cognito_resource_server" "api" {
  identifier   = "https://api.orquestrapay"
  name         = "API Orquestra de Pagamentos"
  user_pool_id = aws_cognito_user_pool.principal.id

  dynamic "scope" {
    for_each = local.descricoes_escopos
    content {
      scope_name        = scope.key
      scope_description = scope.value
    }
  }
}

locals {
  descricoes_escopos = {
    "compras:ler"          = "Consultar compras"
    "compras:escrever"     = "Criar compras"
    "estoque:ler"          = "Consultar estoque"
    "estoque:escrever"     = "Alterar estoque"
    "risco:ler"            = "Consultar risco"
    "pagamentos:ler"       = "Consultar pagamentos"
    "pagamentos:conciliar" = "Conciliar pagamentos"
    "razao:ler"            = "Consultar razao contabil"
    "razao:escrever"       = "Liquidar parcelas da razao contabil"
    "notificacoes:ler"     = "Consultar notificacoes"
    "webhooks:ler"         = "Consultar configuracoes e entregas de webhooks"
    "webhooks:gerenciar"   = "Configurar e reprocessar webhooks"
  }
  escopos_api = [for escopo in aws_cognito_resource_server.api.scope_identifiers : escopo]
}

resource "aws_cognito_user_pool_client" "web" {
  name                                 = "orquestrapay-web"
  user_pool_id                         = aws_cognito_user_pool.principal.id
  generate_secret                      = false
  allowed_oauth_flows_user_pool_client = true
  allowed_oauth_flows                  = ["code"]
  allowed_oauth_scopes                 = ["openid", "email", "profile"]
  callback_urls                        = [var.url_retorno_cognito]
  supported_identity_providers         = ["COGNITO"]
  enable_token_revocation              = true
  prevent_user_existence_errors        = "ENABLED"
  read_attributes = [
    "custom:empresa_id",
    "email",
    "email_verified",
    "family_name",
    "given_name"
  ]
  write_attributes = [
    "email",
    "family_name",
    "given_name"
  ]
  access_token_validity  = 15
  id_token_validity      = 15
  refresh_token_validity = 1
  token_validity_units {
    access_token  = "minutes"
    id_token      = "minutes"
    refresh_token = "days"
  }

  refresh_token_rotation {
    feature                    = "ENABLED"
    retry_grace_period_seconds = 10
  }
}

resource "aws_cognito_user_pool_client" "maquina" {
  name                                 = "orquestrapay-maquina"
  user_pool_id                         = aws_cognito_user_pool.principal.id
  generate_secret                      = true
  allowed_oauth_flows_user_pool_client = true
  allowed_oauth_flows                  = ["client_credentials"]
  allowed_oauth_scopes                 = local.escopos_api
  enable_token_revocation              = true
  prevent_user_existence_errors        = "ENABLED"
  access_token_validity                = 15
  token_validity_units { access_token = "minutes" }
}

resource "aws_cognito_user_pool_domain" "principal" {
  domain       = "${local.prefixo}-${data.aws_caller_identity.atual.account_id}"
  user_pool_id = aws_cognito_user_pool.principal.id
}

resource "aws_cognito_user_group" "grupos" {
  for_each = toset([
    "administrador",
    "operador",
    "financeiro",
    "analista-risco",
    "auditor",
    "desenvolvedor",
    "observabilidade"
  ])

  name         = each.value
  user_pool_id = aws_cognito_user_pool.principal.id
  description  = "Papel ${each.value} da Orquestra de Pagamentos"
}
