ALTER TABLE conciliacao
    DROP CONSTRAINT IF EXISTS ck_conciliacao_quantidades,
    DROP CONSTRAINT IF EXISTS ck_conciliacao_status,
    ALTER COLUMN concluida_em DROP NOT NULL,
    ADD COLUMN provedor VARCHAR(60),
    ADD COLUMN identificador_extrato VARCHAR(100),
    ADD COLUMN hash_extrato CHAR(64),
    ADD COLUMN moeda CHAR(3),
    ADD COLUMN periodo_inicio TIMESTAMPTZ,
    ADD COLUMN periodo_fim TIMESTAMPTZ,
    ADD COLUMN registros_provedor INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN registros_locais INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN registros_duplicados INTEGER NOT NULL DEFAULT 0;

ALTER TABLE conciliacao
    ADD CONSTRAINT ck_conciliacao_quantidades CHECK (
        registros_analisados >= 0
        AND divergencias_encontradas >= 0
        AND registros_provedor >= 0
        AND registros_locais >= 0
        AND registros_duplicados >= 0
    ),
    ADD CONSTRAINT ck_conciliacao_status CHECK (
        status IN ('PROCESSANDO', 'CONCLUIDA', 'CONCLUIDA_COM_DIVERGENCIAS')
    ),
    ADD CONSTRAINT ck_conciliacao_periodo CHECK (
        periodo_inicio IS NULL
        OR periodo_fim IS NULL
        OR periodo_inicio < periodo_fim
    );

CREATE UNIQUE INDEX uk_conciliacao_extrato
    ON conciliacao (id_empresa, provedor, identificador_extrato)
    WHERE provedor IS NOT NULL AND identificador_extrato IS NOT NULL;

CREATE INDEX idx_pagamento_conciliacao
    ON pagamento (id_empresa, provedor, moeda, criado_em)
    WHERE status IN ('AUTORIZADO', 'RECUSADO', 'ESTORNADO');

CREATE TABLE ocorrencia_conciliacao (
    id_ocorrencia UUID PRIMARY KEY,
    id_conciliacao UUID NOT NULL REFERENCES conciliacao (id_conciliacao),
    id_pagamento UUID,
    tipo VARCHAR(50) NOT NULL,
    detalhes TEXT NOT NULL,
    identificada_em TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_ocorrencia_conciliacao_execucao
    ON ocorrencia_conciliacao (id_conciliacao, identificada_em, id_ocorrencia);
