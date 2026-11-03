-- V6는 Seller-SKU 새 schema로 시작. 기존 Campaign 데이터 이전·보존은 지원하지 않음.
-- V1–V5 application table에 행이 있으면 DDL 전에 중단.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM campaign)
       OR EXISTS (SELECT 1 FROM admission_counter)
       OR EXISTS (SELECT 1 FROM admission_ticket)
       OR EXISTS (SELECT 1 FROM inventory_pool)
       OR EXISTS (SELECT 1 FROM inventory_reservation)
       OR EXISTS (SELECT 1 FROM purchase_order)
       OR EXISTS (SELECT 1 FROM payment_attempt) THEN
        RAISE EXCEPTION 'V6 requires a clean-start database: legacy application tables contain data; existing data is not migrated or preserved. Provision an empty database and restart.';
    END IF;
END
$$;

-- V1–V5 table이 모두 비어 있으면 child table부터 제거.
-- `CASCADE`는 금지. 예상하지 못한 dependency가 있으면 migration 실패.
DROP TABLE payment_attempt;
DROP TABLE inventory_reservation;
DROP TABLE admission_ticket;
DROP TABLE purchase_order;
DROP TABLE inventory_pool;
DROP TABLE admission_counter;
DROP TABLE campaign;

CREATE TABLE seller (
    seller_id UUID NOT NULL,
    CONSTRAINT seller_pk PRIMARY KEY (seller_id)
);

CREATE TABLE seller_sku (
    seller_id UUID NOT NULL,
    sku_id VARCHAR(128) NOT NULL,
    max_active_checkouts INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT seller_sku_pk PRIMARY KEY (seller_id, sku_id),
    CONSTRAINT seller_sku_seller_fk FOREIGN KEY (seller_id) REFERENCES seller (seller_id),
    CONSTRAINT seller_sku_max_active_positive CHECK (max_active_checkouts > 0),
    CONSTRAINT seller_sku_code_nonblank CHECK (length(trim(sku_id)) > 0)
);

CREATE TABLE campaign (
    campaign_id UUID NOT NULL,
    seller_id UUID NOT NULL,
    sku_id VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL,
    queue_ticket_ttl_seconds BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT campaign_pk PRIMARY KEY (campaign_id),
    CONSTRAINT campaign_seller_sku_fk FOREIGN KEY (seller_id, sku_id)
        REFERENCES seller_sku (seller_id, sku_id),
    CONSTRAINT campaign_status_valid CHECK (status IN ('CLOSED', 'OPEN')),
    CONSTRAINT campaign_ttl_positive CHECK (queue_ticket_ttl_seconds > 0),
    CONSTRAINT campaign_sku_nonblank CHECK (length(trim(sku_id)) > 0)
);

CREATE TABLE admission_counter (
    seller_id UUID NOT NULL,
    sku_id VARCHAR(128) NOT NULL,
    last_sequence BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT admission_counter_pk PRIMARY KEY (seller_id, sku_id),
    CONSTRAINT admission_counter_seller_sku_fk FOREIGN KEY (seller_id, sku_id)
        REFERENCES seller_sku (seller_id, sku_id),
    CONSTRAINT admission_counter_sequence_nonnegative CHECK (last_sequence >= 0)
);

CREATE TABLE admission_ticket (
    ticket_id UUID NOT NULL,
    campaign_id UUID NOT NULL,
    seller_id UUID NOT NULL,
    sku_id VARCHAR(128) NOT NULL,
    buyer_ref VARCHAR(256) NOT NULL,
    idempotency_key VARCHAR(256) NOT NULL,
    sequence BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    checkout_slot_acquired_at TIMESTAMPTZ,
    checkout_slot_held BOOLEAN NOT NULL,
    CONSTRAINT admission_ticket_pk PRIMARY KEY (ticket_id),
    CONSTRAINT admission_ticket_seller_sku_sequence_unique UNIQUE (seller_id, sku_id, sequence),
    CONSTRAINT admission_ticket_idempotency_unique UNIQUE (campaign_id, idempotency_key),
    CONSTRAINT admission_ticket_campaign_fk FOREIGN KEY (campaign_id) REFERENCES campaign (campaign_id),
    CONSTRAINT admission_ticket_seller_sku_fk FOREIGN KEY (seller_id, sku_id)
        REFERENCES seller_sku (seller_id, sku_id),
    CONSTRAINT admission_ticket_sequence_positive CHECK (sequence > 0),
    CONSTRAINT admission_ticket_status_valid CHECK (status IN (
        'WAITING', 'CHECKOUT_ACTIVE', 'CHECKOUT_STARTED', 'CHECKOUT_SUBMITTED', 'EXPIRED', 'CHECKOUT_FAILED'
    )),
    CONSTRAINT admission_ticket_slot_status_consistent CHECK (
        (checkout_slot_held AND status IN ('CHECKOUT_ACTIVE', 'CHECKOUT_STARTED')) OR
        (NOT checkout_slot_held AND status NOT IN ('CHECKOUT_ACTIVE', 'CHECKOUT_STARTED'))
    ),
    CONSTRAINT admission_ticket_sku_nonblank CHECK (length(trim(sku_id)) > 0),
    CONSTRAINT admission_ticket_buyer_nonblank CHECK (length(trim(buyer_ref)) > 0),
    CONSTRAINT admission_ticket_key_nonblank CHECK (length(trim(idempotency_key)) > 0)
);
CREATE INDEX admission_ticket_fifo_idx ON admission_ticket (seller_id, sku_id, sequence)
    WHERE status = 'WAITING';
CREATE INDEX admission_ticket_expiry_idx ON admission_ticket (expires_at)
    WHERE status IN ('WAITING', 'CHECKOUT_ACTIVE');

CREATE TABLE inventory_pool (
    seller_id UUID NOT NULL,
    sku_id VARCHAR(128) NOT NULL,
    initial_capacity INTEGER NOT NULL,
    available INTEGER NOT NULL,
    reserved INTEGER NOT NULL,
    claimed INTEGER NOT NULL,
    CONSTRAINT inventory_pool_pk PRIMARY KEY (seller_id, sku_id),
    CONSTRAINT inventory_pool_seller_sku_fk FOREIGN KEY (seller_id, sku_id)
        REFERENCES seller_sku (seller_id, sku_id),
    CONSTRAINT inventory_pool_initial_capacity_nonnegative CHECK (initial_capacity >= 0),
    CONSTRAINT inventory_pool_available_nonnegative CHECK (available >= 0),
    CONSTRAINT inventory_pool_reserved_nonnegative CHECK (reserved >= 0),
    CONSTRAINT inventory_pool_claimed_nonnegative CHECK (claimed >= 0),
    CONSTRAINT inventory_pool_conservation CHECK (available + reserved + claimed = initial_capacity)
);

CREATE TABLE inventory_reservation (
    order_id UUID NOT NULL,
    campaign_id UUID NOT NULL,
    seller_id UUID NOT NULL,
    sku_id VARCHAR(128) NOT NULL,
    quantity INTEGER NOT NULL,
    status VARCHAR(16) NOT NULL,
    CONSTRAINT inventory_reservation_pk PRIMARY KEY (order_id),
    CONSTRAINT inventory_reservation_pool_fk FOREIGN KEY (seller_id, sku_id)
        REFERENCES inventory_pool (seller_id, sku_id),
       CONSTRAINT inventory_reservation_quantity_one CHECK (quantity = 1),
    CONSTRAINT inventory_reservation_status CHECK (status IN ('RESERVED', 'CLAIMED', 'RELEASED'))
);
CREATE INDEX inventory_reservation_pool_idx ON inventory_reservation (seller_id, sku_id);

CREATE TABLE purchase_order (
    order_id UUID NOT NULL,
    seller_id UUID NOT NULL,
    campaign_id UUID NOT NULL,
    ticket_id UUID NOT NULL,
    buyer_ref VARCHAR(256) NOT NULL,
    sku_id VARCHAR(128) NOT NULL,
    state VARCHAR(32) NOT NULL,
    checkout_expires_at TIMESTAMPTZ NOT NULL,
    payment_attempt_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT purchase_order_pk PRIMARY KEY (order_id),
    CONSTRAINT purchase_order_ticket_unique UNIQUE (ticket_id),
    CONSTRAINT purchase_order_campaign_fk FOREIGN KEY (campaign_id) REFERENCES campaign (campaign_id),
    CONSTRAINT purchase_order_seller_sku_fk FOREIGN KEY (seller_id, sku_id)
        REFERENCES seller_sku (seller_id, sku_id),
    CONSTRAINT purchase_order_state CHECK (state IN (
        'RESERVED', 'PAYMENT_SUBMITTED', 'PAYMENT_PENDING', 'PAYMENT_UNKNOWN', 'PAYMENT_VOID_PENDING',
        'PAID', 'FAILED', 'EXPIRED', 'CANCEL_PENDING', 'CANCELLED'
    ))
);
CREATE INDEX purchase_order_expiry_idx ON purchase_order (checkout_expires_at)
    WHERE state = 'RESERVED';
CREATE INDEX purchase_order_buyer_unresolved_idx ON purchase_order (campaign_id, buyer_ref)
    WHERE state IN ('PAYMENT_SUBMITTED', 'PAYMENT_PENDING', 'PAYMENT_UNKNOWN', 'PAYMENT_VOID_PENDING', 'CANCEL_PENDING');

CREATE TABLE payment_attempt (
    order_id UUID NOT NULL,
    attempt_id UUID NOT NULL,
    state VARCHAR(32) NOT NULL,
    payment_phase VARCHAR(20) NOT NULL,
    authorization_active BOOLEAN NOT NULL,
    CONSTRAINT payment_attempt_pk PRIMARY KEY (order_id),
    CONSTRAINT payment_attempt_attempt_id_unique UNIQUE (attempt_id),
    CONSTRAINT payment_attempt_state CHECK (state IN (
        'NOT_STARTED', 'AUTHORIZATION_PENDING', 'AUTHORIZED', 'CAPTURE_PENDING', 'CAPTURED', 'DECLINED',
        'UNKNOWN', 'VOID_PENDING', 'VOIDED'
    )),
    CONSTRAINT payment_attempt_payment_phase CHECK (payment_phase IN ('AUTHORIZATION', 'CAPTURE', 'VOID'))
);
