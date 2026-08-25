# Pagamentos e integrações

## Cartão multi-provedor

O serviço de pagamento ordena provedores por prioridade e capacidade. Cada
chamada usa timeout, retry limitado, circuit breaker e bulkhead próprio. Uma
cota token bucket no Redis é compartilhada por todas as réplicas, impedindo que
o escalonamento horizontal ultrapasse o contrato do adquirente. O fallback
acontece somente quando há falha técnica, saturação ou cota temporária; uma
resposta `RECUSADO` encerra a operação no provedor que tomou a decisão.

Os limites de concorrência e vazão são independentes por provedor. Cota
esgotada e indisponibilidade do limitador não contaminam as métricas do circuit
breaker como se fossem falhas do adquirente. Em produção, a política padrão é
falhar de forma controlada quando o Redis não consegue validar a cota; o
trabalhador durável tenta novamente depois sem perder a operação.

O exemplo abaixo cria uma compra em três parcelas:

```json
{
  "idCliente": "cliente-001",
  "emailCliente": "cliente@exemplo.com",
  "moeda": "BRL",
  "pais": "BR",
  "identificadorDispositivo": "dispositivo-001",
  "tokenPagamento": "tok_aprovado",
  "metodoPagamento": "CARTAO",
  "parcelas": 3,
  "itens": [
    {
      "idProduto": "30000000-0000-0000-0000-000000000001",
      "quantidade": 1,
      "precoUnitario": 89.90
    }
  ]
}
```

`GET /api/v1/pagamentos/compras/{idCompra}` informa o provedor utilizado.
`GET /api/v1/transacoes-contabeis/compras/{idCompra}` devolve totais de débito e
crédito, lançamentos e a agenda de parcelas.

Uma parcela pode ser liquidada de forma idempotente:

```http
PATCH /api/v1/transacoes-contabeis/compras/{idCompra}/parcelas/1/liquidacao
X-Empresa-Id: 10000000-0000-0000-0000-000000000001
Content-Type: application/json
```

```json
{ "referencia": "liquidacao-adquirente-2026-0001" }
```

## PIX assíncrono

Para PIX, envie `metodoPagamento=PIX`, uma parcela e nenhum token de cartão. A
criação da cobrança não conclui a compra: ela retorna os dados do QR Code e
mantém o pagamento em `AGUARDANDO_CONFIRMACAO`.

O simulador usa `pix@orquestrapay.local` por padrão. Para um ensaio estritamente
local, `PIX_CHAVE_RECEBEDOR` permite trocar a chave sem gravá-la no código, na
imagem Docker ou no histórico Git. Uma chave real torna o QR potencialmente
pagável; mantenha o valor somente no `.env` ignorado pelo Git e não confirme a
transação durante testes automatizados.

O provedor confirma por `POST /api/v1/webhooks/provedores`, enviando:

- `X-Provedor`;
- `X-Orquestra-Timestamp` em segundos Unix;
- `X-Orquestra-Signature`, HMAC-SHA-256 de `timestamp.corpo`;
- corpo com `idEvento`, `idCompra`, `txid`, `status` e `ocorridoEm`.

Eventos duplicados não repetem o efeito. Assinatura inválida, provedor
desconhecido, evento antigo ou `txid` incompatível são rejeitados.

## Webhooks para sistemas clientes

Uma empresa cadastra seu destino em `PUT /api/v1/webhooks/configuracao`:

```json
{
  "url": "https://erp.exemplo.com/webhooks/orquestrapay",
  "segredo": "um-segredo-com-pelo-menos-trinta-e-dois-caracteres",
  "eventos": ["COMPRA_CONCLUIDA", "COMPRA_RECUSADA", "COMPRA_COMPENSADA"],
  "ativo": true
}
```

O segredo é cifrado e nunca volta na API. Cada tentativa inclui identificador,
timestamp e assinatura HMAC. A entrega usa lease, backoff exponencial, limite
de tentativas e histórico auditável:

- `GET /api/v1/webhooks/entregas` lista entregas;
- `POST /api/v1/webhooks/entregas/{id}/reprocessar` reabre falha definitiva;
- `DELETE /api/v1/webhooks/configuracao` interrompe novos agendamentos.

## Conciliação e divergências

`POST /api/v1/conciliacoes` compara um extrato identificado de até 500 registros
com os pagamentos locais da mesma empresa, provedor, moeda e período. A análise
é bidirecional: encontra registros ausentes em qualquer lado, duplicidades e
diferenças de valor, moeda, status, provedor ou identificador externo. Repetir o
mesmo extrato reaproveita o resultado; reutilizar seu identificador com outro
conteúdo retorna conflito. O histórico pode ser consultado e cada ocorrência
passa de `ABERTA` para `INVESTIGANDO` e `RESOLVIDA`, mantendo observação e
auditoria. Enquanto uma ocorrência equivalente estiver ativa, novas
conciliações apenas atualizam seus detalhes; depois da resolução, uma nova
divergência legítima pode ser aberta sem apagar o histórico anterior.

## Quarentena

Todo serviço com outbox expõe, sob permissão administrativa:

- `GET /api/v1/admin/quarentena?status=ATIVA|RESOLVIDA|TODAS`;
- `GET /api/v1/admin/quarentena/{idEvento}/auditoria`;
- `POST /api/v1/admin/quarentena/{idEvento}/reprocessar`;
- `POST /api/v1/admin/quarentena/{idEvento}/descartar`.

As decisões exigem um motivo. O reprocessamento não apaga o incidente: registra
responsável, erro e tentativas anteriores antes de devolver o evento à fila. O
descarte definitivo também fica auditado e libera, de forma explícita, os eventos
posteriores da mesma compra que estavam protegidos pela garantia de ordem. O
payload do evento não é devolvido pela API administrativa.

## Spring Boot Starter

O módulo `sdk/orquestrapay-spring-boot-starter` registra
`ClienteOrquestraPay` quando `orquestrapay.cliente.id-empresa` estiver
configurado. O cliente adiciona empresa, bearer token opcional, timeout e chave
de idempotência, além de traduzir erros HTTP para `ExcecaoClienteOrquestra`.

```java
@Service
public class ServicoPedidos {

    private final ClienteOrquestraPay cliente;

    public ServicoPedidos(ClienteOrquestraPay cliente) {
        this.cliente = cliente;
    }

    public RespostaCompraCliente cobrar(NovaCompraCliente compra, String idPedido) {
        return cliente.criarCompra("pedido:" + idPedido, compra);
    }
}
```
