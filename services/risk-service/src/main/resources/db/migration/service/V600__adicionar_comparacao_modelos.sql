ALTER TABLE analise_risco
    ADD COLUMN modelo_decisao VARCHAR(80),
    ADD COLUMN versao_modelo_decisao VARCHAR(80);

UPDATE analise_risco
   SET modelo_decisao = 'regras-transacionais',
       versao_modelo_decisao = '1.0.0';

ALTER TABLE analise_risco
    ALTER COLUMN modelo_decisao SET NOT NULL,
    ALTER COLUMN versao_modelo_decisao SET NOT NULL,
    ADD CONSTRAINT uk_analise_risco_empresa_compra UNIQUE (id_empresa, id_compra);

CREATE TABLE comparacao_modelos_risco (
    id_comparacao UUID PRIMARY KEY,
    id_empresa UUID NOT NULL,
    id_compra UUID NOT NULL,
    modelo_champion VARCHAR(80) NOT NULL,
    versao_champion VARCHAR(80) NOT NULL,
    pontuacao_champion INTEGER NOT NULL CHECK (pontuacao_champion BETWEEN 0 AND 100),
    aprovada_champion BOOLEAN NOT NULL,
    sinais_champion TEXT NOT NULL,
    modelo_challenger VARCHAR(80) NOT NULL,
    versao_challenger VARCHAR(80) NOT NULL,
    pontuacao_challenger INTEGER NOT NULL CHECK (pontuacao_challenger BETWEEN 0 AND 100),
    aprovada_challenger BOOLEAN NOT NULL,
    sinais_challenger TEXT NOT NULL,
    classificacao VARCHAR(40) NOT NULL CHECK (
        classificacao IN (
            'DECISAO_CONCORDANTE',
            'CHALLENGER_MAIS_RESTRITIVO',
            'CHALLENGER_MAIS_PERMISSIVO'
        )
    ),
    diferenca_pontuacao INTEGER NOT NULL CHECK (diferenca_pontuacao BETWEEN -100 AND 100),
    avaliada_em TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_comparacao_analise_risco
        FOREIGN KEY (id_empresa, id_compra)
        REFERENCES analise_risco (id_empresa, id_compra),
    CONSTRAINT uk_comparacao_modelo_risco
        UNIQUE (id_empresa, id_compra, modelo_challenger, versao_challenger)
);

CREATE INDEX idx_comparacao_modelos_empresa_tempo
    ON comparacao_modelos_risco (id_empresa, avaliada_em DESC);
