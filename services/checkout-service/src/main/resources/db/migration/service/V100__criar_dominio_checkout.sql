CREATE TABLE compra (
    id_compra UUID PRIMARY KEY,
    id_empresa UUID NOT NULL,
    id_cliente VARCHAR(120) NOT NULL,
    email_cliente VARCHAR(254) NOT NULL,
    moeda CHAR(3) NOT NULL,
    pais CHAR(2) NOT NULL,
    identificador_dispositivo VARCHAR(160) NOT NULL,
    token_pagamento VARCHAR(180) NOT NULL,
    valor_total NUMERIC(19, 2) NOT NULL CHECK (valor_total > 0),
    status VARCHAR(40) NOT NULL,
    id_reserva UUID NOT NULL,
    id_pagamento UUID,
    id_transacao_contabil UUID,
    pagamento_estornado BOOLEAN NOT NULL DEFAULT FALSE,
    estoque_liberado BOOLEAN NOT NULL DEFAULT FALSE,
    motivo TEXT,
    criado_em TIMESTAMPTZ NOT NULL,
    atualizado_em TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_compra_empresa_criacao ON compra (id_empresa, criado_em DESC);

CREATE TABLE item_compra (
    id_item UUID PRIMARY KEY,
    id_compra UUID NOT NULL REFERENCES compra (id_compra),
    id_produto UUID NOT NULL,
    quantidade INTEGER NOT NULL CHECK (quantidade > 0),
    preco_unitario NUMERIC(19, 2) NOT NULL CHECK (preco_unitario > 0),
    UNIQUE (id_compra, id_produto)
);

CREATE TABLE requisicao_idempotente (
    id_empresa UUID NOT NULL,
    chave VARCHAR(120) NOT NULL,
    hash_requisicao CHAR(64) NOT NULL,
    id_compra UUID NOT NULL REFERENCES compra (id_compra),
    criada_em TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (id_empresa, chave)
);

CREATE TABLE historico_saga (
    id_historico UUID PRIMARY KEY,
    id_compra UUID NOT NULL REFERENCES compra (id_compra),
    etapa VARCHAR(80) NOT NULL,
    status_anterior VARCHAR(40),
    status_atual VARCHAR(40) NOT NULL,
    id_evento UUID,
    detalhes TEXT,
    registrado_em TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_historico_saga_compra ON historico_saga (id_compra, registrado_em);
