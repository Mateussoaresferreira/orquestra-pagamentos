terraform {
  required_version = ">= 1.10.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
    archive = {
      source  = "hashicorp/archive"
      version = "~> 2.7"
    }
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.1"
    }
  }
}

provider "aws" {
  region = var.regiao

  default_tags {
    tags = merge(var.tags, {
      Projeto    = var.nome
      Gerenciado = "terraform"
      Ambiente   = var.ambiente
    })
  }
}

data "aws_caller_identity" "atual" {}
data "aws_partition" "atual" {}

data "aws_availability_zones" "disponiveis" {
  state = "available"
}

locals {
  prefixo = "${var.nome}-${var.ambiente}"
  zonas   = slice(data.aws_availability_zones.disponiveis.names, 0, 3)
  repositorios = toset([
    "servico-checkout",
    "servico-estoque",
    "servico-risco",
    "servico-pagamento",
    "servico-razao",
    "servico-notificacao",
    "simulador-provedor"
  ])
}
