CREATE TABLE transacao_contabil (
    id_transacao UUID PRIMARY KEY,
    id_empresa UUID NOT NULL,
    id_compra UUID NOT NULL UNIQUE,
    id_pagamento UUID NOT NULL UNIQUE,
    valor NUMERIC(19, 2) NOT NULL CHECK (valor > 0),
    moeda CHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL,
    motivo TEXT,
    criada_em TIMESTAMPTZ NOT NULL
);

CREATE TABLE lancamento_contabil (
    id_lancamento UUID PRIMARY KEY,
    id_transacao UUID NOT NULL REFERENCES transacao_contabil (id_transacao),
    conta VARCHAR(80) NOT NULL,
    natureza VARCHAR(10) NOT NULL CHECK (natureza IN ('DEBITO', 'CREDITO')),
    valor NUMERIC(19, 2) NOT NULL CHECK (valor > 0),
    moeda CHAR(3) NOT NULL,
    criado_em TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_transacao_contabil_empresa_compra
    ON transacao_contabil (id_empresa, id_compra);

CREATE OR REPLACE FUNCTION impedir_alteracao_lancamento()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Lancamentos contabeis sao imutaveis';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_lancamento_contabil_imutavel
BEFORE UPDATE OR DELETE ON lancamento_contabil
FOR EACH ROW EXECUTE FUNCTION impedir_alteracao_lancamento();

CREATE OR REPLACE FUNCTION validar_partidas_dobradas()
RETURNS TRIGGER AS $$
DECLARE
    total_debitos NUMERIC(19, 2);
    total_creditos NUMERIC(19, 2);
BEGIN
    IF NEW.status = 'REGISTRADA' THEN
        SELECT COALESCE(SUM(valor), 0) INTO total_debitos
          FROM lancamento_contabil
         WHERE id_transacao = NEW.id_transacao AND natureza = 'DEBITO';

        SELECT COALESCE(SUM(valor), 0) INTO total_creditos
          FROM lancamento_contabil
         WHERE id_transacao = NEW.id_transacao AND natureza = 'CREDITO';

        IF total_debitos <> total_creditos OR total_debitos = 0 THEN
            RAISE EXCEPTION 'Transacao contabil desbalanceada: debitos %, creditos %',
                total_debitos, total_creditos;
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_validar_partidas_dobradas
AFTER INSERT OR UPDATE OF status ON transacao_contabil
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION validar_partidas_dobradas();
