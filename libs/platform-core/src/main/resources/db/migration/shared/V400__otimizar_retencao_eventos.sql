CREATE INDEX idx_evento_processado_retencao
    ON evento_processado (processado_em);

CREATE INDEX idx_evento_saida_publicado_retencao
    ON evento_saida (publicado_em)
    WHERE publicado_em IS NOT NULL AND descartado_em IS NULL;

CREATE INDEX idx_evento_saida_descartado_retencao
    ON evento_saida (descartado_em)
    WHERE descartado_em IS NOT NULL;

CREATE INDEX idx_auditoria_quarentena_retencao
    ON auditoria_quarentena (registrada_em);
