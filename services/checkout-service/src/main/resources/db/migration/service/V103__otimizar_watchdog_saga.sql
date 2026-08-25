CREATE INDEX idx_compra_watchdog
    ON compra (status, atualizado_em)
    WHERE status IN (
        'RECEBIDA', 'ESTOQUE_RESERVADO', 'RISCO_APROVADO',
        'PAGAMENTO_AUTORIZADO', 'COMPENSANDO'
    );
