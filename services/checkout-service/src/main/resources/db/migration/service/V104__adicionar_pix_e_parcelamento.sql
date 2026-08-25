ALTER TABLE compra
    ALTER COLUMN token_pagamento DROP NOT NULL,
    ADD COLUMN metodo_pagamento VARCHAR(20) NOT NULL DEFAULT 'CARTAO',
    ADD COLUMN parcelas SMALLINT NOT NULL DEFAULT 1;

ALTER TABLE compra
    DROP CONSTRAINT IF EXISTS ck_compra_status,
    ADD CONSTRAINT ck_compra_status CHECK (status IN (
        'RECEBIDA', 'ESTOQUE_RESERVADO', 'RISCO_APROVADO',
        'AGUARDANDO_PAGAMENTO', 'PAGAMENTO_AUTORIZADO',
        'COMPENSANDO', 'COMPENSADA', 'RECUSADA', 'CONCLUIDA'
    )),
    ADD CONSTRAINT ck_compra_metodo_pagamento CHECK (
        metodo_pagamento IN ('CARTAO', 'PIX')
    ),
    ADD CONSTRAINT ck_compra_parcelas CHECK (parcelas BETWEEN 1 AND 12),
    ADD CONSTRAINT ck_compra_pix_sem_parcelamento CHECK (
        metodo_pagamento <> 'PIX' OR parcelas = 1
    ),
    ADD CONSTRAINT ck_compra_token_por_metodo CHECK (
        metodo_pagamento = 'PIX' OR token_pagamento IS NOT NULL
    );

ALTER TABLE historico_saga
    DROP CONSTRAINT IF EXISTS ck_historico_status_anterior,
    DROP CONSTRAINT IF EXISTS ck_historico_status_atual,
    ADD CONSTRAINT ck_historico_status_anterior CHECK (
        status_anterior IS NULL OR status_anterior IN (
            'RECEBIDA', 'ESTOQUE_RESERVADO', 'RISCO_APROVADO',
            'AGUARDANDO_PAGAMENTO', 'PAGAMENTO_AUTORIZADO',
            'COMPENSANDO', 'COMPENSADA', 'RECUSADA', 'CONCLUIDA'
        )
    ),
    ADD CONSTRAINT ck_historico_status_atual CHECK (status_atual IN (
        'RECEBIDA', 'ESTOQUE_RESERVADO', 'RISCO_APROVADO',
        'AGUARDANDO_PAGAMENTO', 'PAGAMENTO_AUTORIZADO',
        'COMPENSANDO', 'COMPENSADA', 'RECUSADA', 'CONCLUIDA'
    ));
