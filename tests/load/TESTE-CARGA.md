# Teste de carga

O cenário cria compras com chaves únicas enquanto a saga continua trabalhando em segundo plano.

```powershell
docker compose --profile carga run --rm k6
```

Para comparar Virtual Threads com threads de plataforma, recrie os serviços com o perfil abaixo e repita exatamente a mesma carga:

```powershell
$env:SPRING_PROFILES_ACTIVE='platform-threads'
docker compose up -d --force-recreate servico-checkout servico-estoque servico-risco servico-pagamento servico-razao servico-notificacao
docker compose --profile carga run --rm k6
Remove-Item Env:SPRING_PROFILES_ACTIVE
```

Compare taxa de requisições, p95, uso de memória, conexões JDBC e threads no Grafana. Virtual Threads melhoram concorrência de I/O; elas não corrigem consultas lentas nem aumentam o limite do pool de conexões.
