ALTER TABLE notificacao
    ADD CONSTRAINT ck_notificacao_canal
        CHECK (canal IN ('EMAIL')),
    ADD CONSTRAINT ck_notificacao_status
        CHECK (status IN ('PENDENTE', 'ENVIADA')),
    ADD CONSTRAINT ck_notificacao_tentativas
        CHECK (tentativas >= 0);
