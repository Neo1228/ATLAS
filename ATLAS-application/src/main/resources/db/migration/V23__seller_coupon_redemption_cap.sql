ALTER TABLE seller_coupon
    ADD COLUMN max_redemptions BIGINT,
    ADD COLUMN reserved_count BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN redeemed_count BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT seller_coupon_max_redemptions_check CHECK (
        max_redemptions IS NULL OR max_redemptions > 0
    ),
    ADD CONSTRAINT seller_coupon_redemption_counts_check CHECK (
        reserved_count >= 0
        AND redeemed_count >= 0
        AND (
            max_redemptions IS NULL
            OR reserved_count::NUMERIC + redeemed_count::NUMERIC <= max_redemptions::NUMERIC
        )
    );

CREATE TABLE seller_coupon_redemption (
    redemption_id UUID PRIMARY KEY,
    seller_id UUID NOT NULL,
    coupon_id UUID NOT NULL,
    order_id UUID NOT NULL,
    state VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT seller_coupon_redemption_coupon_fk
        FOREIGN KEY (seller_id, coupon_id) REFERENCES seller_coupon(seller_id, coupon_id) ON DELETE RESTRICT,
    CONSTRAINT seller_coupon_redemption_order_fk
        FOREIGN KEY (order_id, seller_id) REFERENCES purchase_order(order_id, seller_id) ON DELETE RESTRICT,
    CONSTRAINT seller_coupon_redemption_unique UNIQUE (coupon_id, order_id),
    CONSTRAINT seller_coupon_redemption_state_check CHECK (state IN ('RESERVED', 'REDEEMED', 'RELEASED'))
);
