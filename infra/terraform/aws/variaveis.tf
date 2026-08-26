variable "nome" {
  description = "Nome curto do produto."
  type        = string
  default     = "orquestrapay"
}

variable "ambiente" {
  description = "Ambiente implantado."
  type        = string
  default     = "portfolio"
}

variable "regiao" {
  description = "Regiao AWS."
  type        = string
  default     = "us-east-1"
}

variable "cidr_vpc" {
  description = "Bloco de rede privado."
  type        = string
  default     = "10.42.0.0/16"
}

variable "cidrs_saida_https" {
  description = "CIDRs privados de proxies ou firewalls autorizados para a saida HTTPS dos workloads."
  type        = list(string)
  default     = []

  validation {
    condition = alltrue([
      for cidr in var.cidrs_saida_https : !contains(["0.0.0.0/0", "::/0"], cidr)
    ])
    error_message = "A saida HTTPS deve passar por proxies ou firewalls com CIDRs restritos."
  }
}

variable "versao_kubernetes" {
  description = "Versao suportada pelo EKS na conta."
  type        = string
  default     = "1.33"
}

variable "cidrs_api_kubernetes" {
  description = "CIDRs autorizados quando a API publica do EKS for habilitada explicitamente."
  type        = list(string)
  default     = []

  validation {
    condition = alltrue([
      for cidr in var.cidrs_api_kubernetes : !contains(["0.0.0.0/0", "::/0"], cidr)
    ])
    error_message = "A API do EKS nao pode ser exposta para toda a Internet."
  }
}

variable "api_kubernetes_publica" {
  description = "Habilita acesso publico ao control plane do EKS. Mantenha false em producao."
  type        = bool
  default     = false
}

variable "tipo_instancia_nos" {
  description = "Tipo das instancias do grupo gerenciado EKS."
  type        = string
  default     = "t3.large"
}

variable "nos_desejados" {
  type    = number
  default = 3
}

variable "nos_minimos" {
  type    = number
  default = 2
}

variable "nos_maximos" {
  type    = number
  default = 6
}

variable "habilitar_karpenter" {
  description = "Habilita provisionamento dinamico de nos EKS com Karpenter e tratamento de interrupcoes Spot. Obrigatorio em producao."
  type        = bool
  default     = false
}

variable "classe_banco" {
  description = "Classe da instancia PostgreSQL compartilhada pelo ambiente de referencia."
  type        = string
  default     = "db.t4g.medium"
}

variable "armazenamento_inicial_banco_gib" {
  description = "Armazenamento inicial do PostgreSQL em GiB."
  type        = number
  default     = 50

  validation {
    condition = (
      var.armazenamento_inicial_banco_gib >= 20 &&
      var.armazenamento_inicial_banco_gib <= 65536 &&
      floor(var.armazenamento_inicial_banco_gib) == var.armazenamento_inicial_banco_gib
    )
    error_message = "O armazenamento inicial do banco deve ser um numero inteiro entre 20 e 65536 GiB."
  }
}

variable "armazenamento_maximo_banco_gib" {
  description = "Teto de autoscaling do armazenamento PostgreSQL em GiB."
  type        = number
  default     = 200

  validation {
    condition = (
      var.armazenamento_maximo_banco_gib >= 20 &&
      var.armazenamento_maximo_banco_gib <= 65536 &&
      floor(var.armazenamento_maximo_banco_gib) == var.armazenamento_maximo_banco_gib
    )
    error_message = "O armazenamento maximo do banco deve ser um numero inteiro entre 20 e 65536 GiB."
  }
}

variable "habilitar_proxy_banco" {
  description = "Habilita o RDS Proxy para controlar tempestades de conexoes ao PostgreSQL. Obrigatorio em producao."
  type        = bool
  default     = false
}

variable "credenciais_proxy_banco" {
  description = "Senhas, com ao menos 24 caracteres, dos usuarios checkout, estoque, risco, pagamento, razao, notificacao e registro."
  type        = map(string)
  sensitive   = true
  default     = {}

  validation {
    condition = alltrue([
      for senha in values(var.credenciais_proxy_banco) : length(senha) >= 24 && length(senha) <= 128
    ])
    error_message = "Cada senha de usuario do RDS Proxy deve ter entre 24 e 128 caracteres."
  }
}

variable "percentual_conexoes_proxy" {
  description = "Percentual maximo das conexoes do PostgreSQL que o proxy pode utilizar."
  type        = number
  default     = 80

  validation {
    condition     = var.percentual_conexoes_proxy >= 1 && var.percentual_conexoes_proxy <= 100
    error_message = "O percentual maximo de conexoes do proxy deve ficar entre 1 e 100."
  }
}

variable "percentual_conexoes_ociosas_proxy" {
  description = "Percentual maximo de conexoes ociosas mantidas pelo proxy."
  type        = number
  default     = 40

  validation {
    condition     = var.percentual_conexoes_ociosas_proxy >= 0 && var.percentual_conexoes_ociosas_proxy <= 100
    error_message = "O percentual de conexoes ociosas do proxy deve ficar entre 0 e 100."
  }
}

variable "tempo_emprestimo_proxy_segundos" {
  description = "Tempo maximo que uma requisicao aguarda uma conexao do pool do proxy."
  type        = number
  default     = 10

  validation {
    condition     = var.tempo_emprestimo_proxy_segundos >= 1 && var.tempo_emprestimo_proxy_segundos <= 300
    error_message = "O tempo de emprestimo do proxy deve ficar entre 1 e 300 segundos."
  }
}

variable "banco_multi_az" {
  description = "Replica o PostgreSQL em outra zona de disponibilidade. Obrigatorio em producao."
  type        = bool
  default     = false
}

variable "retencao_backup_banco_dias" {
  description = "Quantidade de dias de backups automaticos do PostgreSQL."
  type        = number
  default     = 7

  validation {
    condition     = var.retencao_backup_banco_dias >= 0 && var.retencao_backup_banco_dias <= 35
    error_message = "A retencao de backup do RDS deve estar entre 0 e 35 dias."
  }
}

variable "classe_redis" {
  type    = string
  default = "cache.t4g.small"
}

variable "redis_multi_az" {
  description = "Mantem replica do Redis com failover automatico. Obrigatorio em producao."
  type        = bool
  default     = false
}

variable "retencao_snapshot_redis_dias" {
  description = "Quantidade de dias dos snapshots automaticos do Redis."
  type        = number
  default     = 0

  validation {
    condition     = var.retencao_snapshot_redis_dias >= 0 && var.retencao_snapshot_redis_dias <= 35
    error_message = "A retencao de snapshots do Redis deve estar entre 0 e 35 dias."
  }
}

variable "senha_redis" {
  description = "Senha do Redis, entregue fora do codigo por TF_VAR_senha_redis e replicada no Secrets Manager."
  type        = string
  sensitive   = true

  validation {
    condition     = length(var.senha_redis) >= 16 && length(var.senha_redis) <= 128
    error_message = "A senha do Redis deve ter entre 16 e 128 caracteres."
  }
}

variable "proteger_exclusao" {
  description = "Ativa protecao contra exclusao nos recursos com estado."
  type        = bool
  default     = false
}

variable "retencao_logs_dias" {
  description = "Retencao dos logs da aplicacao no CloudWatch. Producao exige pelo menos 365 dias."
  type        = number
  default     = 30

  validation {
    condition = contains([
      1, 3, 5, 7, 14, 30, 60, 90, 120, 150, 180, 365,
      400, 545, 731, 1096, 1827, 2192, 2557, 2922, 3288, 3653
    ], var.retencao_logs_dias)
    error_message = "Informe um periodo de retencao aceito pelo CloudWatch Logs."
  }
}

variable "url_retorno_cognito" {
  description = "URL autorizada para o fluxo Authorization Code com PKCE."
  type        = string
  default     = "http://localhost:3000/autenticacao/retorno"
}

variable "habilitar_waf" {
  description = "Habilita o AWS WAF regional que protege o ALB publico da API."
  type        = bool
  default     = false
}

variable "limite_waf_por_ip" {
  description = "Quantidade maxima de requisicoes por IP na janela de avaliacao do WAF."
  type        = number
  default     = 2000

  validation {
    condition     = var.limite_waf_por_ip >= 100 && var.limite_waf_por_ip <= 2000000
    error_message = "O limite do WAF por IP deve ficar entre 100 e 2.000.000."
  }
}

variable "janela_avaliacao_waf_segundos" {
  description = "Janela explicita, em segundos, usada pelo limite por IP do AWS WAF."
  type        = number
  default     = 60

  validation {
    condition     = contains([60, 120, 300, 600], var.janela_avaliacao_waf_segundos)
    error_message = "A janela do WAF deve ser 60, 120, 300 ou 600 segundos."
  }
}

variable "email_alertas" {
  description = "Email opcional para assinar os alertas SNS."
  type        = string
  default     = ""
}

variable "tags" {
  type    = map(string)
  default = {}
}
