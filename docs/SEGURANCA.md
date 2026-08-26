# Segurança

## Modelo de identidade

Cada API é um OAuth2 Resource Server. Em produção, o token JWT precisa cumprir quatro condições antes de chegar ao domínio:

1. assinatura válida e chave obtida do emissor configurado;
2. `issuer` igual ao provedor confiável;
3. `token_use=access`;
4. `client_id` ou audiência compatível com a aplicação.

Usuários humanos recebem papéis pelos grupos do Cognito, como `OPERADOR`,
`FINANCEIRO`, `AUDITOR` e `ADMINISTRADOR`. Integrações máquina a máquina usam
escopos mínimos, como `compras:escrever`, `estoque:ler` ou
`pagamentos:conciliar`. A matriz de rotas aceita somente o papel ou escopo
necessário para cada método; uma consulta não concede automaticamente uma
operação de escrita.

O cliente web usa Authorization Code com PKCE e recebe apenas `openid`, `email`
e `profile`. O cliente técnico usa Client Credentials e escopos da API. Tokens
de ambos os clientes precisam pertencer à lista explícita de clientes aceitos.

Em produção, somente o estado básico de saúde é anônimo. Swagger exige o papel
`DESENVOLVEDOR` ou `AUDITOR`, enquanto métricas e demais endpoints do Actuator
exigem `OBSERVABILIDADE`. O health público nunca inclui detalhes dos componentes.

## Isolamento entre empresas

Para usuários humanos, o claim `custom:empresa_id` identifica a empresa
autenticada. O atributo é somente leitura para o cliente web no Cognito. Para o
cliente máquina, a empresa é vinculada por configuração confiável do servidor e
qualquer claim de empresa presente no token é ignorado. Isso impede que uma
credencial técnica escolha outro tenant.

Quando a segurança está habilitada, o filtro rejeita:

- token sem empresa;
- identificador de empresa inválido;
- `X-Empresa-Id` diferente do claim do token.

Os repositórios incluem `id_empresa` nas consultas externas e nas verificações
internas de idempotência por compra ou reserva. O cabeçalho local existe para
testes, não como fonte confiável em produção.

## Proteção de pagamento

O token recebido pelo checkout é cifrado com AES-256-GCM antes da persistência.
Cada cifra identifica a versão da chave, usa vetor de inicialização aleatório e
associa o identificador da compra como dado autenticado. O chaveiro aceita uma
chave ativa para novas cifras e chaves anteriores somente para leitura,
permitindo rotação gradual sem indisponibilidade.

A chave entra por `CHAVE_CRIPTOGRAFIA_TOKEN` e deve vir de um gerenciador de segredos. Ela nunca deve ser registrada em log, imagem ou manifesto versionado.

A aplicação não possui chave de fallback e inicia com JWT habilitado por padrão.
Somente a bancada local do Compose define explicitamente
`SEGURANCA_HABILITADA=false` e uma chave descartável para facilitar os testes.

O token permanece cifrado no PostgreSQL, no conteúdo da outbox e durante o
transporte pelo Kafka. Ele só é revelado em memória pelo serviço de pagamento,
imediatamente antes da chamada ao provedor. O banco de pagamentos armazena
somente uma impressão HMAC-SHA-256, derivada da chave mestra com separação de
contexto, para não permitir testes offline contra tokens previsíveis.

O Redis da bancada local exige senha, e a integração de pagamento envia uma
chave de API em todas as chamadas ao simulador de provedor. O Helm obtém a
senha do Redis, a chave criptográfica e a credencial do provedor pelo External Secrets;
em uma integração real, essa autenticação deve evoluir para OAuth2 máquina a
máquina ou mTLS conforme o contrato do adquirente.

## Segredos e cloud

- RDS mantém a senha mestre em AWS Secrets Manager e ela é usada somente pelo job de preparação.
- Cada banco lógico possui usuário e senha próprios; um serviço não recebe a credencial de outro domínio.
- Cada usuário PostgreSQL possui um segredo dedicado, compartilhado somente entre RDS Proxy e o respectivo pod pelo External Secrets.
- O segredo geral da aplicação contém Redis, criptografia e integrações, mas não duplica senhas PostgreSQL.
- External Secrets materializa somente os valores necessários no namespace.
- A conta do External Secrets possui uma função IRSA exclusiva para ler a senha mestre, os segredos PostgreSQL e o segredo geral.
- Os consumidores Kafka recebem apenas a função IRSA do MSK; o simulador, o registry e os jobs não recebem permissão AWS.
- MSK Serverless aceita autenticação IAM; Redis usa TLS e autenticação no perfil cloud.
- Secrets do EKS e alertas SNS usam chaves KMS administradas pelo projeto, com rotação habilitada.
- a saída HTTPS de produção passa somente por proxy ou firewall privado informado no Terraform.
- RDS Proxy exige TLS, limita o pool central e possui alarmes para saturação e espera de conexão.
- Karpenter usa Pod Identity, função dedicada, AMI AL2023 fixada, IMDSv2 obrigatório e fila criptografada de interrupções Spot.

## Proteções de API

- as APIs autenticam por bearer token, não por cookie, não criam sessão e
  ignoram CSRF somente em `/api/**` e `/actuator/**`; escritas fora dessas rotas
  continuam exigindo o token CSRF do Spring;
- validação Bean Validation em todos os corpos de entrada;
- rejeição de caracteres Unicode de controle ou formatação em identificadores,
  tokens e chaves de idempotência, além de formato empresarial estrito para o
  email que receberá a notificação;
- validação de moedas pelo catálogo ISO 4217 antes de iniciar a saga;
- restrições `CHECK` no PostgreSQL para estados, moeda, país, quantidades e
  impressão criptográfica do token;
- token bucket atômico no Redis com cota global e cota independente por empresa;
- controle de admissão por réplica, limitado antes do checkout ocupar todas as
  conexões e devolver filas com latência crescente;
- limite de 1 MiB para corpos HTTP e 16 KiB para cabeçalhos;
- chave de idempotência com hash do corpo;
- respostas de erro no formato Problem Details, sem exceção, mensagem interna,
  erro de binding ou stack trace;
- cabeçalho `Cross-Origin-Resource-Policy: same-origin` em todas as respostas;
- endpoints de Actuator limitados ao necessário;
- contêiner sem privilégios e sistema de arquivos raiz somente leitura no Helm;
- políticas de rede: observabilidade acessa métricas, pagamento acessa o provedor
  e serviços acessam o registry; os demais pares permanecem isolados.
- ingresso público protegido por HTTPS, política TLS e AWS WAFv2 com reputação
  de IP, entradas maliciosas conhecidas, limite por IP, janela de avaliação
  explícita e logs no CloudWatch;
- callbacks de provedor e webhooks empresariais usam HMAC-SHA-256 sobre
  `timestamp.corpo`, comparação em tempo constante e proteção contra replay;
- destinos de webhook exigem HTTPS público em produção, rejeitam credenciais,
  query, fragmento, IP local/privado e redirecionamento HTTP;
- chamadas de webhook possuem timeout curto e não mantêm transação JDBC aberta.

## Ameaças consideradas

| Ameaça | Controle |
|---|---|
| Repetição de compra ou cobrança | Idempotency-Key, inbox e identificadores estáveis |
| Acesso a outra empresa | claim de empresa, filtro e consultas com `id_empresa` |
| Token JWT emitido para outro cliente | validação de emissor, uso e cliente/audiência |
| Tenant forjado por cliente técnico | vínculo servidor-cliente e claim técnico ignorado |
| Vazamento do token de pagamento no banco | AES-256-GCM e segredo externo |
| Evento adulterado ou incompatível | contrato Avro versionado e registry |
| Terceiro indisponível | timeout, retentativa limitada e circuit breaker |
| Exaustão do checkout | WAF, token bucket global/empresa, admissão local e `429` com `Retry-After` |
| Tempestade de conexões ao escalar pods | Hikari pequeno por pod, RDS Proxy, TLS, teto central e alarmes antecipados |
| Interrupção de nó Spot | fila SQS, dreno do Karpenter, PDB, SIGTERM e consumidores idempotentes |
| Falha silenciosa de consumo | retentativas, quarentena, DLT, métricas e alertas |
| SSRF por URL de webhook | HTTPS público, resolução validada e redirects bloqueados |
| Callback PIX forjado ou repetido | HMAC, janela temporal e idempotência por evento |

## Limites do ambiente local

O Compose usa senhas conhecidas, Kafka sem TLS e APIs sem autenticação. Ele é uma bancada de desenvolvimento e não representa uma configuração segura para exposição pública.

## Antes de produção

- substituir todos os valores de exemplo;
- fornecer certificado ACM; o Helm recusa ingresso público sem HTTPS e política TLS explícita;
- associar ao ingresso o ARN do WAF criado pelo Terraform;
- restringir origens CORS e redes de saída;
- definir retenção e mascaramento de dados pessoais;
- habilitar backup, restauração testada e auditoria de acessos;
- executar análise de dependências, imagens e testes adversariais;
- executar o DAST do OWASP ZAP contra o OpenAPI implantado;
- configurar alarmes com destinatários reais.
