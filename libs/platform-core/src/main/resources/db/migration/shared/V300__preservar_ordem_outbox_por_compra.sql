CREATE INDEX idx_evento_saida_compra_ordem_pendente
    ON evento_saida (id_compra, ordem)
    WHERE publicado_em IS NULL;
