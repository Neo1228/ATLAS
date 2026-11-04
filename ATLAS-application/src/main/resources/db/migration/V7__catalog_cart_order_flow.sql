-- V7 does not infer missing Product names or prices from legacy Seller-SKU rows.
-- Refuse before DDL when V6 application data exists; the application never resets it.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM seller)
       OR EXISTS (SELECT 1 FROM seller_sku)
       OR EXISTS (SELECT 1 FROM campaign)
       OR EXISTS (SELECT 1 FROM admission_counter)
       OR EXISTS (SELECT 1 FROM admission_ticket)
       OR EXISTS (SELECT 1 FROM inventory_pool)
       OR EXISTS (SELECT 1 FROM inventory_reservation)
       OR EXISTS (SELECT 1 FROM purchase_order)
       OR EXISTS (SELECT 1 FROM payment_attempt) THEN
        RAISE EXCEPTION 'V7 requires an empty local application schema: existing Seller-SKU rows have no Product/price mapping. Export needed data outside the application and provision a clean database.';
    END IF;
END
$$;

CREATE TABLE product (
    product_id UUID NOT NULL,
    seller_id UUID NOT NULL,
    name TEXT NOT NULL,
    description TEXT,
    CONSTRAINT product_pk PRIMARY KEY (product_id),
    CONSTRAINT product_seller_fk FOREIGN KEY (seller_id) REFERENCES seller (seller_id),
    CONSTRAINT product_seller_identity_unique UNIQUE (product_id, seller_id),
    CONSTRAINT product_name_nonblank CHECK (length(trim(name)) > 0)
);
CREATE INDEX product_seller_idx ON product (seller_id, product_id);

ALTER TABLE seller_sku DROP CONSTRAINT seller_sku_max_active_positive;
ALTER TABLE seller_sku DROP COLUMN max_active_checkouts;
ALTER TABLE seller_sku ADD COLUMN product_id UUID NOT NULL;
ALTER TABLE seller_sku ADD COLUMN price_won BIGINT NOT NULL;
ALTER TABLE seller_sku
    ADD CONSTRAINT seller_sku_product_fk FOREIGN KEY (product_id, seller_id)
        REFERENCES product (product_id, seller_id),
    ADD CONSTRAINT seller_sku_product_variant_unique UNIQUE (product_id, seller_id, sku_id),
    ADD CONSTRAINT seller_sku_price_positive CHECK (price_won > 0);
CREATE INDEX seller_sku_product_idx ON seller_sku (product_id, seller_id);

CREATE TABLE seller_sku_admission_policy (
    seller_id UUID NOT NULL,
    sku_id VARCHAR(128) NOT NULL,
    max_active_checkouts INTEGER NOT NULL,
    CONSTRAINT seller_sku_admission_policy_pk PRIMARY KEY (seller_id, sku_id),
    CONSTRAINT seller_sku_admission_policy_sku_fk FOREIGN KEY (seller_id, sku_id)
        REFERENCES seller_sku (seller_id, sku_id),
    CONSTRAINT seller_sku_admission_policy_positive CHECK (max_active_checkouts > 0)
);

CREATE TABLE shopping_cart (
    cart_id UUID NOT NULL,
    seller_id UUID NOT NULL,
    buyer_ref VARCHAR(256) NOT NULL,
    CONSTRAINT shopping_cart_pk PRIMARY KEY (cart_id),
    CONSTRAINT shopping_cart_seller_fk FOREIGN KEY (seller_id) REFERENCES seller (seller_id),
    CONSTRAINT shopping_cart_buyer_seller_unique UNIQUE (buyer_ref, seller_id),
    CONSTRAINT shopping_cart_id_seller_unique UNIQUE (cart_id, seller_id),
    CONSTRAINT shopping_cart_buyer_nonblank CHECK (length(trim(buyer_ref)) > 0)
);

CREATE TABLE shopping_cart_item (
    cart_item_id UUID NOT NULL,
    cart_id UUID NOT NULL,
    seller_id UUID NOT NULL,
    sku_id VARCHAR(128) NOT NULL,
    quantity INTEGER NOT NULL,
    CONSTRAINT shopping_cart_item_pk PRIMARY KEY (cart_item_id),
    CONSTRAINT shopping_cart_item_cart_seller_fk FOREIGN KEY (cart_id, seller_id)
        REFERENCES shopping_cart (cart_id, seller_id) ON DELETE CASCADE,
    CONSTRAINT shopping_cart_item_seller_sku_fk FOREIGN KEY (seller_id, sku_id)
        REFERENCES seller_sku (seller_id, sku_id),
    CONSTRAINT shopping_cart_item_cart_sku_unique UNIQUE (cart_id, sku_id),
    CONSTRAINT shopping_cart_item_quantity_positive CHECK (quantity > 0)
);

ALTER TABLE purchase_order ALTER COLUMN campaign_id DROP NOT NULL;
ALTER TABLE purchase_order ALTER COLUMN ticket_id DROP NOT NULL;
ALTER TABLE purchase_order DROP CONSTRAINT purchase_order_seller_sku_fk;
ALTER TABLE purchase_order DROP COLUMN sku_id;
ALTER TABLE purchase_order ADD COLUMN order_source VARCHAR(16) NOT NULL DEFAULT 'CAMPAIGN';
ALTER TABLE purchase_order ADD COLUMN checkout_key VARCHAR(256);
ALTER TABLE purchase_order
    ADD CONSTRAINT purchase_order_source_valid CHECK (order_source IN ('CAMPAIGN', 'CART')),
    ADD CONSTRAINT purchase_order_source_context CHECK (
        (order_source = 'CAMPAIGN' AND campaign_id IS NOT NULL AND ticket_id IS NOT NULL) OR
        (order_source = 'CART' AND campaign_id IS NULL AND ticket_id IS NULL)
    ),
    ADD CONSTRAINT purchase_order_checkout_key_nonblank CHECK (
        checkout_key IS NULL OR length(trim(checkout_key)) > 0
    );
CREATE UNIQUE INDEX purchase_order_checkout_key_unique
    ON purchase_order (seller_id, buyer_ref, checkout_key)
    WHERE checkout_key IS NOT NULL;
CREATE INDEX purchase_order_seller_buyer_unresolved_idx
    ON purchase_order (seller_id, buyer_ref)
    WHERE state IN ('PAYMENT_SUBMITTED', 'PAYMENT_PENDING', 'PAYMENT_UNKNOWN', 'PAYMENT_VOID_PENDING', 'CANCEL_PENDING');

CREATE TABLE order_item (
    order_item_id UUID NOT NULL,
    order_id UUID NOT NULL,
    product_id UUID NOT NULL,
    seller_id UUID NOT NULL,
    sku_id VARCHAR(128) NOT NULL,
    product_name TEXT NOT NULL,
    quantity INTEGER NOT NULL,
    unit_price_won BIGINT NOT NULL,
    CONSTRAINT order_item_pk PRIMARY KEY (order_item_id),
    CONSTRAINT order_item_order_fk FOREIGN KEY (order_id) REFERENCES purchase_order (order_id),
    CONSTRAINT order_item_product_sku_fk FOREIGN KEY (product_id, seller_id, sku_id)
        REFERENCES seller_sku (product_id, seller_id, sku_id),
    CONSTRAINT order_item_order_sku_unique UNIQUE (order_id, seller_id, sku_id),
    CONSTRAINT order_item_name_nonblank CHECK (length(trim(product_name)) > 0),
    CONSTRAINT order_item_quantity_positive CHECK (quantity > 0),
    CONSTRAINT order_item_price_positive CHECK (unit_price_won > 0)
);

ALTER TABLE inventory_reservation DROP CONSTRAINT inventory_reservation_pk;
ALTER TABLE inventory_reservation DROP CONSTRAINT inventory_reservation_quantity_one;
ALTER TABLE inventory_reservation ALTER COLUMN campaign_id DROP NOT NULL;
ALTER TABLE inventory_reservation ADD COLUMN reservation_id UUID NOT NULL;
ALTER TABLE inventory_reservation
    ADD CONSTRAINT inventory_reservation_pk PRIMARY KEY (reservation_id),
    ADD CONSTRAINT inventory_reservation_order_sku_unique UNIQUE (order_id, seller_id, sku_id),
    ADD CONSTRAINT inventory_reservation_quantity_positive CHECK (quantity > 0);
CREATE INDEX inventory_reservation_order_idx ON inventory_reservation (order_id, seller_id, sku_id);
