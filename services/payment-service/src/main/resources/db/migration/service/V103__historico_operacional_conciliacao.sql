CREATE TABLE conciliacao (
    id_conciliacao UUID PRIMARY KEY,
    id_empresa UUID NOT NULL,
    registros_analisados INTEGER NOT NULL,
    divergencias_encontradas INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL,
    iniciada_em TIMESTAMPTZ NOT NULL,
    concluida_em TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_conciliacao_quantidades CHECK (
        registros_analisados >= 0
        AND divergencias_encontradas >= 0
        AND divergencias_encontradas <= registros_analisados
    ),
    CONSTRAINT ck_conciliacao_status CHECK (status IN ('CONCLUIDA', 'CONCLUIDA_COM_DIVERGENCIAS'))
);

CREATE INDEX idx_conciliacao_empresa
    ON conciliacao (id_empresa, concluida_em DESC);

CREATE TABLE auditoria_divergencia (
    id_auditoria UUID PRIMARY KEY,
    id_divergencia UUID NOT NULL REFERENCES divergencia_conciliacao (id_divergencia),
    id_empresa UUID NOT NULL,
    status_anterior VARCHAR(20) NOT NULL,
    status_novo VARCHAR(20) NOT NULL,
    observacao TEXT,
    alterada_em TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_auditoria_divergencia
    ON auditoria_divergencia (id_divergencia, alterada_em);
