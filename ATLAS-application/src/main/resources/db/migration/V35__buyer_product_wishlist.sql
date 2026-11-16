CREATE TABLE buyer_wishlist_item (
    buyer_ref VARCHAR(256) NOT NULL,
    product_id UUID NOT NULL REFERENCES product (product_id) ON DELETE CASCADE,
    saved_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (buyer_ref, product_id),
    CONSTRAINT buyer_wishlist_item_buyer_nonblank CHECK (length(btrim(buyer_ref)) > 0)
);

CREATE INDEX idx_buyer_wishlist_item_saved_order
    ON buyer_wishlist_item (buyer_ref, saved_at DESC, product_id DESC);
