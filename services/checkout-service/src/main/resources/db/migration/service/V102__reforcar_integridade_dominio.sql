ALTER TABLE compra
    ADD CONSTRAINT ck_compra_moeda
        CHECK (moeda ~ '^[A-Z]{3}$'),
    ADD CONSTRAINT ck_compra_pais
        CHECK (pais ~ '^[A-Z]{2}$'),
    ADD CONSTRAINT ck_compra_status
        CHECK (status IN (
            'RECEBIDA', 'ESTOQUE_RESERVADO', 'RISCO_APROVADO',
            'PAGAMENTO_AUTORIZADO', 'COMPENSANDO', 'COMPENSADA',
            'RECUSADA', 'CONCLUIDA'
        ));

ALTER TABLE historico_saga
    ADD CONSTRAINT ck_historico_status_anterior
        CHECK (status_anterior IS NULL OR status_anterior IN (
            'RECEBIDA', 'ESTOQUE_RESERVADO', 'RISCO_APROVADO',
            'PAGAMENTO_AUTORIZADO', 'COMPENSANDO', 'COMPENSADA',
            'RECUSADA', 'CONCLUIDA'
        )),
    ADD CONSTRAINT ck_historico_status_atual
        CHECK (status_atual IN (
            'RECEBIDA', 'ESTOQUE_RESERVADO', 'RISCO_APROVADO',
            'PAGAMENTO_AUTORIZADO', 'COMPENSANDO', 'COMPENSADA',
            'RECUSADA', 'CONCLUIDA'
        ));
