ALTER TABLE reserva_estoque
    ADD CONSTRAINT ck_reserva_estoque_status
        CHECK (status IN ('RESERVADA', 'RECUSADA', 'LIBERADA'));
