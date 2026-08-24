ALTER TABLE analise_risco
    ADD CONSTRAINT ck_analise_risco_valor
        CHECK (valor_total > 0),
    ADD CONSTRAINT ck_analise_risco_pais
        CHECK (pais ~ '^[A-Z]{2}$');
