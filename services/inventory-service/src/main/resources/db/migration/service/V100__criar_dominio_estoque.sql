CREATE TABLE saldo_estoque (
    id_empresa UUID NOT NULL,
    id_produto UUID NOT NULL,
    quantidade_disponivel INTEGER NOT NULL CHECK (quantidade_disponivel >= 0),
    quantidade_reservada INTEGER NOT NULL DEFAULT 0 CHECK (quantidade_reservada >= 0),
    atualizado_em TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (id_empresa, id_produto)
);

CREATE TABLE reserva_estoque (
    id_reserva UUID PRIMARY KEY,
    id_empresa UUID NOT NULL,
    id_compra UUID NOT NULL UNIQUE,
    status VARCHAR(30) NOT NULL,
    motivo TEXT,
    criada_em TIMESTAMPTZ NOT NULL,
    atualizada_em TIMESTAMPTZ NOT NULL
);

CREATE TABLE item_reserva (
    id_reserva UUID NOT NULL REFERENCES reserva_estoque (id_reserva),
    id_produto UUID NOT NULL,
    quantidade INTEGER NOT NULL CHECK (quantidade > 0),
    PRIMARY KEY (id_reserva, id_produto)
);

CREATE INDEX idx_reserva_estoque_empresa_compra
    ON reserva_estoque (id_empresa, id_compra);
