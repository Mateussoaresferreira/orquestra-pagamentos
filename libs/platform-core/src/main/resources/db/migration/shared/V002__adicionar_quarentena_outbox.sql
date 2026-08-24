ALTER TABLE evento_saida
    ADD COLUMN proxima_tentativa_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN descartado_em TIMESTAMPTZ;

DROP INDEX idx_evento_saida_pendente;

CREATE INDEX idx_evento_saida_pendente
    ON evento_saida (proxima_tentativa_em, ordem)
    WHERE publicado_em IS NULL AND descartado_em IS NULL;
