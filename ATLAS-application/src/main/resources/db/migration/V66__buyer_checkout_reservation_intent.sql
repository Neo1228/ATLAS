ALTER TABLE buyer_checkout
    DROP CONSTRAINT buyer_checkout_state_valid;
ALTER TABLE buyer_checkout
    ADD CONSTRAINT buyer_checkout_state_valid CHECK (state IN (
        'RESERVATION_PENDING', 'RESERVED', 'PAYMENT_PENDING', 'PAYMENT_UNKNOWN',
        'INVENTORY_CLAIM_PENDING', 'CANCEL_PENDING', 'PAID', 'FAILED', 'CANCELLED', 'EXPIRED'
    ));

ALTER TABLE purchase_order
    DROP CONSTRAINT purchase_order_state;
ALTER TABLE purchase_order
    ADD CONSTRAINT purchase_order_state CHECK (state IN (
        'RESERVATION_PENDING', 'RESERVED', 'PAYMENT_SUBMITTED', 'PAYMENT_PENDING',
        'PAYMENT_UNKNOWN', 'PAYMENT_VOID_PENDING', 'INVENTORY_CLAIM_PENDING', 'PAID',
        'FAILED', 'EXPIRED', 'CANCEL_PENDING', 'CANCELLED'
    ));

ALTER TABLE purchase_order
    ADD COLUMN reservation_workflow_id UUID
        REFERENCES order_inventory_reservation_workflow(workflow_id);
ALTER TABLE purchase_order
    ADD CONSTRAINT purchase_order_reservation_workflow_unique UNIQUE (reservation_workflow_id);
