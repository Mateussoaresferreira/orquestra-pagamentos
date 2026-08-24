CREATE TABLE pagamento (
    id_pagamento UUID PRIMARY KEY,
    id_empresa UUID NOT NULL,
    id_compra UUID NOT NULL UNIQUE,
    valor NUMERIC(19, 2) NOT NULL CHECK (valor > 0),
    moeda CHAR(3) NOT NULL,
    impressao_token CHAR(64) NOT NULL,
    status VARCHAR(30) NOT NULL,
    id_autorizacao VARCHAR(100),
    motivo TEXT,
    criado_em TIMESTAMPTZ NOT NULL,
    atualizado_em TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_pagamento_empresa_criacao
    ON pagamento (id_empresa, criado_em DESC);

CREATE TABLE tentativa_pagamento (
    id_tentativa UUID PRIMARY KEY,
    id_pagamento UUID NOT NULL REFERENCES pagamento (id_pagamento),
    operacao VARCHAR(30) NOT NULL,
    resultado VARCHAR(30) NOT NULL,
    detalhes TEXT,
    realizada_em TIMESTAMPTZ NOT NULL
);

CREATE TABLE divergencia_conciliacao (
    id_divergencia UUID PRIMARY KEY,
    id_empresa UUID NOT NULL,
    id_pagamento UUID,
    tipo VARCHAR(50) NOT NULL,
    detalhes TEXT NOT NULL,
    identificada_em TIMESTAMPTZ NOT NULL
);
