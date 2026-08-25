ALTER TABLE pagamento
    DROP CONSTRAINT IF EXISTS ck_pagamento_status,
    DROP CONSTRAINT IF EXISTS ck_pagamento_impressao_token;

ALTER TABLE pagamento
    ALTER COLUMN impressao_token DROP NOT NULL,
    ADD COLUMN metodo_pagamento VARCHAR(20) NOT NULL DEFAULT 'CARTAO',
    ADD COLUMN parcelas SMALLINT NOT NULL DEFAULT 1,
    ADD COLUMN token_protegido TEXT,
    ADD COLUMN provedor VARCHAR(60),
    ADD COLUMN txid VARCHAR(100),
    ADD COLUMN copia_cola_pix TEXT,
    ADD COLUMN imagem_qr_code_base64 TEXT,
    ADD COLUMN expira_em TIMESTAMPTZ;

ALTER TABLE pagamento
    ADD CONSTRAINT ck_pagamento_status CHECK (status IN (
        'PENDENTE', 'PROCESSANDO', 'AGUARDANDO_CONFIRMACAO',
        'AUTORIZADO', 'RECUSADO', 'ESTORNO_PENDENTE',
        'ESTORNANDO', 'FALHA_TECNICA', 'EXPIRADO', 'ESTORNADO'
    )),
    ADD CONSTRAINT ck_pagamento_metodo CHECK (metodo_pagamento IN ('CARTAO', 'PIX')),
    ADD CONSTRAINT ck_pagamento_parcelas CHECK (parcelas BETWEEN 1 AND 12),
    ADD CONSTRAINT ck_pagamento_impressao_token CHECK (
        impressao_token IS NULL OR impressao_token ~ '^[0-9a-f]{64}$'
    ),
    ADD CONSTRAINT ck_pagamento_pix_sem_parcelamento CHECK (
        metodo_pagamento <> 'PIX' OR parcelas = 1
    );

CREATE UNIQUE INDEX uk_pagamento_txid
    ON pagamento (provedor, txid)
    WHERE txid IS NOT NULL;

CREATE TABLE operacao_pagamento (
    id_operacao UUID PRIMARY KEY,
    id_pagamento UUID NOT NULL REFERENCES pagamento (id_pagamento),
    tipo VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL,
    tentativas INTEGER NOT NULL DEFAULT 0,
    proxima_tentativa_em TIMESTAMPTZ NOT NULL,
    bloqueado_ate TIMESTAMPTZ,
    token_bloqueio UUID,
    ultimo_erro TEXT,
    criada_em TIMESTAMPTZ NOT NULL,
    atualizada_em TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_operacao_pagamento UNIQUE (id_pagamento, tipo),
    CONSTRAINT ck_operacao_tipo CHECK (tipo IN ('AUTORIZAR_CARTAO', 'CRIAR_PIX', 'ESTORNAR')),
    CONSTRAINT ck_operacao_status CHECK (status IN (
        'PENDENTE', 'PROCESSANDO', 'CONCLUIDA', 'FALHA_DEFINITIVA'
    )),
    CONSTRAINT ck_operacao_tentativas CHECK (tentativas >= 0)
);

CREATE INDEX idx_operacao_pagamento_fila
    ON operacao_pagamento (proxima_tentativa_em, criada_em)
    WHERE status IN ('PENDENTE', 'PROCESSANDO');

CREATE TABLE webhook_provedor_recebido (
    id_webhook UUID PRIMARY KEY,
    provedor VARCHAR(60) NOT NULL,
    id_evento_provedor UUID NOT NULL,
    id_pagamento UUID REFERENCES pagamento (id_pagamento),
    hash_conteudo CHAR(64) NOT NULL,
    status_processamento VARCHAR(30) NOT NULL,
    motivo TEXT,
    recebido_em TIMESTAMPTZ NOT NULL,
    processado_em TIMESTAMPTZ,
    CONSTRAINT uk_webhook_provedor_evento UNIQUE (provedor, id_evento_provedor),
    CONSTRAINT ck_webhook_provedor_status CHECK (
        status_processamento IN ('RECEBIDO', 'PROCESSADO', 'IGNORADO', 'REJEITADO')
    )
);

ALTER TABLE tentativa_pagamento
    DROP CONSTRAINT IF EXISTS ck_tentativa_operacao,
    DROP CONSTRAINT IF EXISTS ck_tentativa_resultado;

ALTER TABLE tentativa_pagamento
    ADD CONSTRAINT ck_tentativa_operacao CHECK (
        operacao IN ('AUTORIZACAO', 'CRIACAO_PIX', 'CONFIRMACAO_PIX', 'ESTORNO')
    );

ALTER TABLE divergencia_conciliacao
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ABERTA',
    ADD COLUMN observacao_resolucao TEXT,
    ADD COLUMN atualizado_em TIMESTAMPTZ,
    ADD COLUMN resolvido_em TIMESTAMPTZ,
    ADD CONSTRAINT ck_divergencia_status CHECK (
        status IN ('ABERTA', 'INVESTIGANDO', 'RESOLVIDA')
    );

CREATE INDEX idx_divergencia_empresa_status
    ON divergencia_conciliacao (id_empresa, status, identificada_em DESC);
