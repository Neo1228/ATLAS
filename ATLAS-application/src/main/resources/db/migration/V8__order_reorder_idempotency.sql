CREATE TABLE order_reorder_request (
    seller_id UUID NOT NULL,
    buyer_ref VARCHAR(256) NOT NULL,
    idempotency_key VARCHAR(256) NOT NULL,
    source_order_id UUID NOT NULL,
    confirm_merge BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT order_reorder_request_pk PRIMARY KEY (seller_id, buyer_ref, idempotency_key),
    CONSTRAINT order_reorder_request_seller_fk FOREIGN KEY (seller_id) REFERENCES seller (seller_id),
    CONSTRAINT order_reorder_request_source_order_fk FOREIGN KEY (source_order_id)
        REFERENCES purchase_order (order_id),
    CONSTRAINT order_reorder_request_buyer_nonblank CHECK (length(trim(buyer_ref)) > 0),
    CONSTRAINT order_reorder_request_key_nonblank CHECK (length(trim(idempotency_key)) > 0)
);
