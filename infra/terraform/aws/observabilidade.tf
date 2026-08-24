data "aws_iam_policy_document" "chave_alertas" {
  statement {
    sid       = "AdministracaoDaConta"
    actions   = ["kms:*"]
    resources = ["*"]
    principals {
      type        = "AWS"
      identifiers = ["arn:${data.aws_partition.atual.partition}:iam::${data.aws_caller_identity.atual.account_id}:root"]
    }
  }

  statement {
    sid = "PublicacaoDeAlertas"
    actions = [
      "kms:Decrypt",
      "kms:GenerateDataKey*"
    ]
    resources = ["*"]
    principals {
      type        = "Service"
      identifiers = ["cloudwatch.amazonaws.com", "sns.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.atual.account_id]
    }
  }
}

resource "aws_kms_key" "alertas" {
  description             = "Criptografia dos alertas operacionais ${local.prefixo}"
  enable_key_rotation     = true
  deletion_window_in_days = var.proteger_exclusao ? 30 : 7
  policy                  = data.aws_iam_policy_document.chave_alertas.json
}

resource "aws_kms_alias" "alertas" {
  name          = "alias/${local.prefixo}-alertas"
  target_key_id = aws_kms_key.alertas.key_id
}

resource "aws_sns_topic" "alertas" {
  name              = "${local.prefixo}-alertas"
  kms_master_key_id = aws_kms_key.alertas.arn
}

resource "aws_sns_topic_subscription" "email" {
  count     = var.email_alertas == "" ? 0 : 1
  topic_arn = aws_sns_topic.alertas.arn
  protocol  = "email"
  endpoint  = var.email_alertas
}

resource "aws_cloudwatch_metric_alarm" "cpu_banco" {
  alarm_name          = "${local.prefixo}-banco-cpu-alta"
  alarm_description   = "CPU do PostgreSQL acima de 80% por dez minutos"
  namespace           = "AWS/RDS"
  metric_name         = "CPUUtilization"
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 2
  threshold           = 80
  comparison_operator = "GreaterThanThreshold"
  dimensions          = { DBInstanceIdentifier = aws_db_instance.principal.identifier }
  alarm_actions       = [aws_sns_topic.alertas.arn]
  ok_actions          = [aws_sns_topic.alertas.arn]
}

resource "aws_cloudwatch_metric_alarm" "armazenamento_banco" {
  alarm_name          = "${local.prefixo}-banco-armazenamento-baixo"
  alarm_description   = "Menos de 10 GiB livres no PostgreSQL"
  namespace           = "AWS/RDS"
  metric_name         = "FreeStorageSpace"
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 2
  threshold           = 10737418240
  comparison_operator = "LessThanThreshold"
  dimensions          = { DBInstanceIdentifier = aws_db_instance.principal.identifier }
  alarm_actions       = [aws_sns_topic.alertas.arn]
}

resource "aws_cloudwatch_log_group" "aplicacao" {
  name              = "/${var.nome}/${var.ambiente}/aplicacao"
  retention_in_days = var.retencao_logs_dias

  lifecycle {
    precondition {
      condition     = lower(var.ambiente) != "producao" || var.retencao_logs_dias >= 365
      error_message = "Producao exige retencao de logs por pelo menos 365 dias."
    }
  }
}
