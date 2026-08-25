ALTER TABLE notificacao
    ADD COLUMN token_bloqueio UUID;

UPDATE notificacao
   SET status = 'PENDENTE',
       bloqueado_ate = NULL,
       token_bloqueio = NULL
 WHERE status = 'PROCESSANDO';

ALTER TABLE notificacao
    ADD CONSTRAINT ck_notificacao_lease
        CHECK (
            (status = 'PROCESSANDO' AND bloqueado_ate IS NOT NULL AND token_bloqueio IS NOT NULL)
            OR
            (status <> 'PROCESSANDO' AND bloqueado_ate IS NULL AND token_bloqueio IS NULL)
        );
