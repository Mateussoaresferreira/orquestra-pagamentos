ALTER TABLE evento_saida
    ADD COLUMN reprocessamentos INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN resolvido_em TIMESTAMPTZ,
    ADD COLUMN motivo_resolucao TEXT;

ALTER TABLE evento_saida
    ADD CONSTRAINT ck_evento_saida_reprocessamentos
        CHECK (reprocessamentos >= 0),
    ADD CONSTRAINT ck_evento_saida_resolucao_quarentena
        CHECK (
            (resolvido_em IS NULL AND motivo_resolucao IS NULL)
            OR (descartado_em IS NOT NULL
                AND resolvido_em IS NOT NULL
                AND motivo_resolucao IS NOT NULL)
        );

ALTER TABLE auditoria_quarentena
    DROP CONSTRAINT IF EXISTS ck_auditoria_quarentena_acao,
    ADD COLUMN tentativas_anteriores INTEGER,
    ADD COLUMN erro_anterior TEXT,
    ADD COLUMN motivo TEXT NOT NULL DEFAULT 'Motivo nao informado';

ALTER TABLE auditoria_quarentena
    ADD CONSTRAINT ck_auditoria_quarentena_acao
        CHECK (acao IN ('REPROCESSAR', 'DESCARTAR_DEFINITIVAMENTE')),
    ADD CONSTRAINT ck_auditoria_quarentena_tentativas
        CHECK (tentativas_anteriores IS NULL OR tentativas_anteriores >= 0);

CREATE INDEX idx_evento_saida_quarentena_ativa
    ON evento_saida (id_empresa, descartado_em DESC)
    WHERE descartado_em IS NOT NULL AND resolvido_em IS NULL;
