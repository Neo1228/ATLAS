ALTER TABLE inventory_reservation
    DROP CONSTRAINT inventory_reservation_status;

ALTER TABLE inventory_reservation
    ADD CONSTRAINT inventory_reservation_status
    CHECK (status IN ('RESERVED', 'CLAIMED', 'RELEASED', 'EXPIRED', 'CANCELLED'));
