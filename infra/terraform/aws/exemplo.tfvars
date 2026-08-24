ambiente                     = "portfolio"
regiao                       = "us-east-1"
versao_kubernetes            = "1.33"
tipo_instancia_nos           = "t3.large"
nos_desejados                = 3
nos_minimos                  = 2
nos_maximos                  = 6
classe_banco                 = "db.t4g.medium"
banco_multi_az               = false
retencao_backup_banco_dias   = 7
classe_redis                 = "cache.t4g.small"
redis_multi_az               = false
retencao_snapshot_redis_dias = 0
proteger_exclusao            = false
retencao_logs_dias           = 30
api_kubernetes_publica       = false
cidrs_api_kubernetes         = []
cidrs_saida_https            = []
email_alertas                = ""

tags = {
  CentroCusto = "portfolio"
  Responsavel = "mateus"
}
