CREATE TABLE product_review (
    review_id UUID PRIMARY KEY DEFAULT uuidv7(),
    buyer_ref VARCHAR(256) NOT NULL,
    product_id UUID NOT NULL REFERENCES product (product_id) ON DELETE CASCADE,
    order_item_id UUID NOT NULL UNIQUE REFERENCES order_item (order_item_id) ON DELETE RESTRICT,
    rating SMALLINT NOT NULL CHECK (rating BETWEEN 1 AND 5),
    body VARCHAR(2000) NOT NULL CHECK (length(btrim(body)) BETWEEN 1 AND 2000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT product_review_buyer_nonblank CHECK (length(btrim(buyer_ref)) > 0),
    CONSTRAINT product_review_buyer_product_unique UNIQUE (buyer_ref, product_id)
);

CREATE INDEX idx_product_review_product_created
    ON product_review (product_id, created_at DESC, review_id DESC);
