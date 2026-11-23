CREATE TABLE distributed_inventory_workflow (
    workflow_id UUID PRIMARY KEY,
    order_id UUID NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL UNIQUE,
    workflow_state VARCHAR(32) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    last_error_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT distributed_inventory_workflow_attempt_nonnegative CHECK (attempt_count >= 0),
    CONSTRAINT distributed_inventory_workflow_state CHECK (
        workflow_state IN (
            'PENDING', 'RETRY_PENDING', 'RESERVED', 'COMPENSATING',
            'COMPENSATION_REQUIRED', 'FAILED', 'NEEDS_REVIEW'
        )
    )
);

CREATE INDEX distributed_inventory_workflow_due_idx
    ON distributed_inventory_workflow (next_attempt_at, workflow_id)
    WHERE workflow_state IN ('PENDING', 'RETRY_PENDING', 'COMPENSATING', 'COMPENSATION_REQUIRED');

CREATE TABLE distributed_inventory_location_command (
    workflow_id UUID NOT NULL REFERENCES distributed_inventory_workflow (workflow_id) ON DELETE CASCADE,
    fulfillment_location_id UUID NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL UNIQUE,
    command_state VARCHAR(32) NOT NULL,
    remote_reservation_id UUID,
    last_error_code VARCHAR(64),
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (workflow_id, fulfillment_location_id),
    CONSTRAINT distributed_inventory_location_state CHECK (
        command_state IN ('PENDING', 'RETRY_PENDING', 'RESERVED', 'FAILED', 'RELEASE_PENDING', 'RELEASED')
    )
);

CREATE TABLE distributed_inventory_location_item (
    workflow_id UUID NOT NULL,
    fulfillment_location_id UUID NOT NULL,
    sku_id VARCHAR(96) NOT NULL,
    quantity INTEGER NOT NULL,
    PRIMARY KEY (workflow_id, fulfillment_location_id, sku_id),
    CONSTRAINT distributed_inventory_location_item_quantity CHECK (quantity > 0),
    CONSTRAINT distributed_inventory_location_item_command_fk
        FOREIGN KEY (workflow_id, fulfillment_location_id)
        REFERENCES distributed_inventory_location_command (workflow_id, fulfillment_location_id)
        ON DELETE CASCADE
);
