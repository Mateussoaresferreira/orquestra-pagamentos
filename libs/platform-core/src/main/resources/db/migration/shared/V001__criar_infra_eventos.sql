CREATE TABLE evento_saida (
    id_evento UUID PRIMARY KEY,
    ordem BIGSERIAL NOT NULL UNIQUE,
    tipo VARCHAR(120) NOT NULL,
    versao INTEGER NOT NULL,
    id_correlacao UUID NOT NULL,
    id_empresa UUID NOT NULL,
    id_compra UUID NOT NULL,
    origem VARCHAR(80) NOT NULL,
    conteudo TEXT NOT NULL,
    traceparent VARCHAR(128),
    ocorrido_em TIMESTAMPTZ NOT NULL,
    publicado_em TIMESTAMPTZ,
    tentativas INTEGER NOT NULL DEFAULT 0,
    ultimo_erro TEXT
);

CREATE INDEX idx_evento_saida_pendente
    ON evento_saida (ordem)
    WHERE publicado_em IS NULL;

CREATE TABLE evento_processado (
    id_evento UUID NOT NULL,
    consumidor VARCHAR(120) NOT NULL,
    processado_em TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (id_evento, consumidor)
);
