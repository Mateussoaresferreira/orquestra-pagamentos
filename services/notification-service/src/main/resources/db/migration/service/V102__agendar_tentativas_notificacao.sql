ALTER TABLE notificacao
    ADD COLUMN proxima_tentativa_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN bloqueado_ate TIMESTAMPTZ,
    ADD COLUMN falha_definitiva_em TIMESTAMPTZ;

ALTER TABLE notificacao DROP CONSTRAINT ck_notificacao_status;

ALTER TABLE notificacao
    ADD CONSTRAINT ck_notificacao_status
        CHECK (status IN ('PENDENTE', 'PROCESSANDO', 'ENVIADA', 'FALHA_DEFINITIVA'));

DROP INDEX idx_notificacao_pendente;

CREATE INDEX idx_notificacao_pendente
    ON notificacao (proxima_tentativa_em, criada_em)
    WHERE status IN ('PENDENTE', 'PROCESSANDO');
