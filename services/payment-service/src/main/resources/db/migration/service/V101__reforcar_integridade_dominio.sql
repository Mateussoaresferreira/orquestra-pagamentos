ALTER TABLE pagamento
    ADD CONSTRAINT ck_pagamento_moeda
        CHECK (moeda ~ '^[A-Z]{3}$'),
    ADD CONSTRAINT ck_pagamento_impressao_token
        CHECK (impressao_token ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT ck_pagamento_status
        CHECK (status IN ('AUTORIZADO', 'RECUSADO', 'ESTORNADO'));

ALTER TABLE tentativa_pagamento
    ADD CONSTRAINT ck_tentativa_operacao
        CHECK (operacao IN ('AUTORIZACAO', 'ESTORNO')),
    ADD CONSTRAINT ck_tentativa_resultado
        CHECK (resultado IN ('AUTORIZADO', 'RECUSADO', 'ESTORNADO'));
