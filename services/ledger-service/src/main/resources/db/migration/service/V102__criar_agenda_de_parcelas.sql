CREATE TABLE parcela_recebivel (
    id_parcela UUID PRIMARY KEY,
    id_transacao UUID NOT NULL REFERENCES transacao_contabil (id_transacao),
    numero SMALLINT NOT NULL CHECK (numero BETWEEN 1 AND 12),
    total_parcelas SMALLINT NOT NULL CHECK (total_parcelas BETWEEN 1 AND 12),
    valor NUMERIC(19, 2) NOT NULL CHECK (valor > 0),
    vencimento DATE NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('AGENDADA', 'LIQUIDADA', 'CANCELADA')),
    referencia_liquidacao VARCHAR(100),
    criada_em TIMESTAMPTZ NOT NULL,
    liquidada_em TIMESTAMPTZ,
    CONSTRAINT uk_parcela_transacao_numero UNIQUE (id_transacao, numero),
    CONSTRAINT ck_parcela_numero_total CHECK (numero <= total_parcelas),
    CONSTRAINT ck_parcela_liquidacao CHECK (
        (status = 'LIQUIDADA' AND referencia_liquidacao IS NOT NULL AND liquidada_em IS NOT NULL)
        OR (status <> 'LIQUIDADA' AND referencia_liquidacao IS NULL AND liquidada_em IS NULL)
    )
);

CREATE INDEX idx_parcela_recebivel_vencimento_pendente
    ON parcela_recebivel (vencimento, id_transacao)
    WHERE status = 'AGENDADA';

CREATE TABLE auditoria_parcela (
    id_auditoria UUID PRIMARY KEY,
    id_parcela UUID NOT NULL REFERENCES parcela_recebivel (id_parcela),
    status_anterior VARCHAR(20) NOT NULL,
    status_novo VARCHAR(20) NOT NULL,
    referencia VARCHAR(100),
    registrada_em TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_auditoria_parcela_parcela
    ON auditoria_parcela (id_parcela, registrada_em);

CREATE OR REPLACE FUNCTION preservar_dados_financeiros_parcela()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Parcelas financeiras nao podem ser excluidas';
    END IF;

    IF NEW.id_transacao IS DISTINCT FROM OLD.id_transacao
       OR NEW.numero IS DISTINCT FROM OLD.numero
       OR NEW.total_parcelas IS DISTINCT FROM OLD.total_parcelas
       OR NEW.valor IS DISTINCT FROM OLD.valor
       OR NEW.vencimento IS DISTINCT FROM OLD.vencimento
       OR NEW.criada_em IS DISTINCT FROM OLD.criada_em THEN
        RAISE EXCEPTION 'Dados financeiros da parcela sao imutaveis';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_preservar_dados_financeiros_parcela
BEFORE UPDATE OR DELETE ON parcela_recebivel
FOR EACH ROW EXECUTE FUNCTION preservar_dados_financeiros_parcela();

CREATE OR REPLACE FUNCTION validar_total_parcelas()
RETURNS TRIGGER AS $$
DECLARE
    soma_parcelas NUMERIC(19, 2);
    quantidade_parcelas INTEGER;
    valor_transacao NUMERIC(19, 2);
BEGIN
    SELECT COALESCE(SUM(valor), 0), COUNT(*)
      INTO soma_parcelas, quantidade_parcelas
      FROM parcela_recebivel
     WHERE id_transacao = NEW.id_transacao;

    SELECT valor INTO valor_transacao
      FROM transacao_contabil
     WHERE id_transacao = NEW.id_transacao;

    IF quantidade_parcelas = NEW.total_parcelas AND soma_parcelas <> valor_transacao THEN
        RAISE EXCEPTION 'A soma das parcelas % difere do valor da transacao %',
            soma_parcelas, valor_transacao;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_validar_total_parcelas
AFTER INSERT ON parcela_recebivel
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION validar_total_parcelas();
