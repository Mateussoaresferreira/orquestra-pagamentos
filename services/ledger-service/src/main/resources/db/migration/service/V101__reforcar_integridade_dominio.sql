ALTER TABLE transacao_contabil
    ADD CONSTRAINT ck_transacao_moeda
        CHECK (moeda ~ '^[A-Z]{3}$'),
    ADD CONSTRAINT ck_transacao_status
        CHECK (status IN ('ABERTA', 'REJEITADA', 'REGISTRADA'));

ALTER TABLE lancamento_contabil
    ADD CONSTRAINT ck_lancamento_moeda
        CHECK (moeda ~ '^[A-Z]{3}$');
