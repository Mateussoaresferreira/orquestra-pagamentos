CREATE TABLE notificacao (
    id_notificacao UUID PRIMARY KEY,
    id_evento UUID NOT NULL UNIQUE,
    id_empresa UUID NOT NULL,
    id_compra UUID NOT NULL,
    canal VARCHAR(20) NOT NULL,
    destinatario VARCHAR(254) NOT NULL,
    assunto VARCHAR(160) NOT NULL,
    mensagem TEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    tentativas INTEGER NOT NULL DEFAULT 0,
    ultimo_erro TEXT,
    criada_em TIMESTAMPTZ NOT NULL,
    enviada_em TIMESTAMPTZ
);

CREATE INDEX idx_notificacao_pendente
    ON notificacao (criada_em)
    WHERE status = 'PENDENTE';

CREATE INDEX idx_notificacao_empresa_compra
    ON notificacao (id_empresa, id_compra, criada_em);
