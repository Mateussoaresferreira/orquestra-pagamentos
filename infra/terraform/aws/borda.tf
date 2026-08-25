resource "aws_cloudwatch_log_group" "waf" {
  count             = var.habilitar_waf || lower(var.ambiente) == "producao" ? 1 : 0
  name              = "aws-waf-logs-${local.prefixo}"
  retention_in_days = var.retencao_logs_dias
}

resource "aws_wafv2_web_acl" "api" {
  count = var.habilitar_waf || lower(var.ambiente) == "producao" ? 1 : 0

  name  = "${local.prefixo}-api"
  scope = "REGIONAL"

  default_action {
    allow {}
  }

  rule {
    name     = "limite-por-ip"
    priority = 1

    action {
      block {}
    }

    statement {
      rate_based_statement {
        aggregate_key_type    = "IP"
        limit                 = var.limite_waf_por_ip
        evaluation_window_sec = var.janela_avaliacao_waf_segundos
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "limite-por-ip"
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "reputacao-ip-aws"
    priority = 10

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesAmazonIpReputationList"
        vendor_name = "AWS"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "reputacao-ip-aws"
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "entradas-inseguras-aws"
    priority = 20

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesKnownBadInputsRuleSet"
        vendor_name = "AWS"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "entradas-inseguras-aws"
      sampled_requests_enabled   = true
    }
  }

  visibility_config {
    cloudwatch_metrics_enabled = true
    metric_name                = "${local.prefixo}-api"
    sampled_requests_enabled   = true
  }

  lifecycle {
    precondition {
      condition     = lower(var.ambiente) != "producao" || var.habilitar_waf
      error_message = "O WAF deve estar habilitado no ambiente de producao."
    }
  }
}

resource "aws_wafv2_web_acl_logging_configuration" "api" {
  count = var.habilitar_waf || lower(var.ambiente) == "producao" ? 1 : 0

  resource_arn            = aws_wafv2_web_acl.api[0].arn
  log_destination_configs = [aws_cloudwatch_log_group.waf[0].arn]
}
