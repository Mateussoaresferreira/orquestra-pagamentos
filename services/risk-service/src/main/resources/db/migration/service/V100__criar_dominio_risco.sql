CREATE TABLE analise_risco (
    id_analise UUID PRIMARY KEY,
    id_empresa UUID NOT NULL,
    id_compra UUID NOT NULL UNIQUE,
    id_cliente VARCHAR(120) NOT NULL,
    identificador_dispositivo VARCHAR(160) NOT NULL,
    valor_total NUMERIC(19, 2) NOT NULL,
    pais CHAR(2) NOT NULL,
    pontuacao INTEGER NOT NULL CHECK (pontuacao BETWEEN 0 AND 100),
    aprovada BOOLEAN NOT NULL,
    sinais TEXT NOT NULL,
    analisada_em TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_analise_risco_cliente_tempo
    ON analise_risco (id_empresa, id_cliente, analisada_em DESC);

CREATE INDEX idx_analise_risco_dispositivo_tempo
    ON analise_risco (id_empresa, identificador_dispositivo, analisada_em DESC);
