ALTER TABLE evento_saida
    ADD COLUMN bloqueado_ate TIMESTAMPTZ,
    ADD COLUMN token_bloqueio UUID;

CREATE TABLE auditoria_quarentena (
    id_auditoria UUID PRIMARY KEY,
    id_evento UUID NOT NULL REFERENCES evento_saida (id_evento),
    acao VARCHAR(40) NOT NULL,
    responsavel VARCHAR(160) NOT NULL,
    detalhes TEXT,
    registrada_em TIMESTAMPTZ NOT NULL
);

ALTER TABLE auditoria_quarentena
    ADD CONSTRAINT ck_auditoria_quarentena_acao
        CHECK (acao IN ('REPROCESSAR'));

CREATE INDEX idx_auditoria_quarentena_evento
    ON auditoria_quarentena (id_evento, registrada_em DESC);
