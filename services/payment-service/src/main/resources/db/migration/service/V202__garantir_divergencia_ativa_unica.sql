WITH divergencias_classificadas AS (
    SELECT id_divergencia,
           status AS status_anterior,
           ROW_NUMBER() OVER (
               PARTITION BY id_empresa, id_pagamento, tipo
               ORDER BY identificada_em DESC, id_divergencia
           ) AS posicao
      FROM divergencia_conciliacao
     WHERE status IN ('ABERTA', 'INVESTIGANDO')
), divergencias_consolidadas AS (
    UPDATE divergencia_conciliacao AS divergencia
       SET status = 'RESOLVIDA',
           observacao_resolucao = 'Consolidada automaticamente em outra divergencia ativa equivalente',
           atualizado_em = CURRENT_TIMESTAMP,
           resolvido_em = CURRENT_TIMESTAMP
      FROM divergencias_classificadas AS classificada
     WHERE divergencia.id_divergencia = classificada.id_divergencia
       AND classificada.posicao > 1
    RETURNING divergencia.id_divergencia,
              divergencia.id_empresa,
              classificada.status_anterior
)
INSERT INTO auditoria_divergencia (
    id_auditoria, id_divergencia, id_empresa,
    status_anterior, status_novo, observacao, alterada_em
)
SELECT gen_random_uuid(), id_divergencia, id_empresa,
       status_anterior, 'RESOLVIDA',
       'Consolidada automaticamente em outra divergencia ativa equivalente',
       CURRENT_TIMESTAMP
  FROM divergencias_consolidadas;

CREATE UNIQUE INDEX uk_divergencia_conciliacao_ativa
    ON divergencia_conciliacao (id_empresa, id_pagamento, tipo) NULLS NOT DISTINCT
    WHERE status IN ('ABERTA', 'INVESTIGANDO');
