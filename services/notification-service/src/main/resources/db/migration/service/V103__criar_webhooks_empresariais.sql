CREATE TABLE configuracao_webhook (
    id_empresa UUID PRIMARY KEY,
    url TEXT NOT NULL,
    segredo_protegido TEXT NOT NULL,
    eventos TEXT NOT NULL,
    ativo BOOLEAN NOT NULL DEFAULT TRUE,
    criado_em TIMESTAMPTZ NOT NULL,
    atualizado_em TIMESTAMPTZ NOT NULL
);

CREATE TABLE entrega_webhook (
    id_entrega UUID PRIMARY KEY,
    id_empresa UUID NOT NULL REFERENCES configuracao_webhook (id_empresa),
    id_evento UUID NOT NULL,
    id_compra UUID NOT NULL,
    tipo_evento VARCHAR(60) NOT NULL,
    conteudo TEXT NOT NULL,
    status VARCHAR(30) NOT NULL,
    tentativas INTEGER NOT NULL DEFAULT 0,
    proxima_tentativa_em TIMESTAMPTZ NOT NULL,
    bloqueado_ate TIMESTAMPTZ,
    token_bloqueio UUID,
    ultimo_status_http INTEGER,
    ultimo_erro TEXT,
    criada_em TIMESTAMPTZ NOT NULL,
    atualizada_em TIMESTAMPTZ NOT NULL,
    entregue_em TIMESTAMPTZ,
    falha_definitiva_em TIMESTAMPTZ,
    CONSTRAINT uk_entrega_webhook_evento UNIQUE (id_empresa, id_evento),
    CONSTRAINT ck_entrega_webhook_status CHECK (
        status IN ('PENDENTE', 'PROCESSANDO', 'ENTREGUE', 'FALHA_DEFINITIVA')
    ),
    CONSTRAINT ck_entrega_webhook_tentativas CHECK (tentativas >= 0)
);

CREATE INDEX idx_entrega_webhook_fila
    ON entrega_webhook (proxima_tentativa_em, criada_em)
    WHERE status IN ('PENDENTE', 'PROCESSANDO');

CREATE INDEX idx_entrega_webhook_empresa
    ON entrega_webhook (id_empresa, criada_em DESC);

CREATE TABLE tentativa_webhook (
    id_tentativa UUID PRIMARY KEY,
    id_entrega UUID NOT NULL REFERENCES entrega_webhook (id_entrega),
    numero_tentativa INTEGER NOT NULL,
    status_http INTEGER,
    resultado VARCHAR(30) NOT NULL,
    detalhes TEXT,
    realizada_em TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_tentativa_webhook_resultado CHECK (
        resultado IN ('SUCESSO', 'FALHA_TRANSITORIA', 'FALHA_DEFINITIVA')
    )
);

CREATE INDEX idx_tentativa_webhook_entrega
    ON tentativa_webhook (id_entrega, numero_tentativa);
