CREATE TABLE seller_coupon (
    coupon_id UUID PRIMARY KEY,
    seller_id UUID NOT NULL REFERENCES seller(seller_id) ON DELETE RESTRICT,
    code VARCHAR(32) NOT NULL,
    discount_amount_won BIGINT NOT NULL,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT seller_coupon_owner_code_unique UNIQUE (seller_id, code),
    CONSTRAINT seller_coupon_owner_id_unique UNIQUE (seller_id, coupon_id),
    CONSTRAINT seller_coupon_positive_amount CHECK (discount_amount_won > 0),
    CONSTRAINT seller_coupon_valid_window CHECK (starts_at < ends_at)
);

ALTER TABLE purchase_order
    ADD COLUMN seller_coupon_id UUID,
    ADD COLUMN seller_coupon_code VARCHAR(32),
    ADD COLUMN coupon_discount_won BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT purchase_order_seller_coupon_fk
        FOREIGN KEY (seller_id, seller_coupon_id)
        REFERENCES seller_coupon(seller_id, coupon_id) ON DELETE RESTRICT,
    ADD CONSTRAINT purchase_order_coupon_snapshot_check CHECK (
        coupon_discount_won >= 0
        AND (
            (seller_coupon_id IS NULL AND seller_coupon_code IS NULL AND coupon_discount_won = 0)
            OR (seller_coupon_id IS NOT NULL AND seller_coupon_code IS NOT NULL AND coupon_discount_won > 0)
        )
    );

ALTER TABLE order_item
    ADD COLUMN coupon_discount_won BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT order_item_coupon_discount_check CHECK (
        coupon_discount_won >= 0
        AND quantity > 0
        AND coupon_discount_won < unit_price_won * quantity
        AND coupon_discount_won / quantity
            + CASE WHEN coupon_discount_won % quantity = 0 THEN 0 ELSE 1 END < unit_price_won
    );
