-- V14 is a clean-start-only transition; never mutate populated legacy commerce data.
DO $$
BEGIN
 IF EXISTS (SELECT 1 FROM shopping_cart)
 OR EXISTS (SELECT 1 FROM shopping_cart_item)
 OR EXISTS (SELECT 1 FROM purchase_order)
 OR EXISTS (SELECT 1 FROM payment_attempt)
 OR EXISTS (SELECT 1 FROM settlement_journal)
 OR EXISTS (SELECT 1 FROM settlement_entry) THEN
  RAISE EXCEPTION 'V14 requires a clean-start database; export and provision clean schema outside application';
 END IF;
END
$$;

CREATE TABLE buyer_checkout (
 checkout_id UUID PRIMARY KEY DEFAULT uuidv7(),
 buyer_ref VARCHAR(256) NOT NULL,
 idempotency_key VARCHAR(256) NOT NULL,
 state VARCHAR(32) NOT NULL,
 total_won BIGINT NOT NULL,
 created_at TIMESTAMPTZ NOT NULL,
 updated_at TIMESTAMPTZ NOT NULL,
 CONSTRAINT buyer_checkout_buyer_nonblank CHECK (length(trim(buyer_ref)) > 0),
 CONSTRAINT buyer_checkout_key_nonblank CHECK (length(trim(idempotency_key)) > 0),
 CONSTRAINT buyer_checkout_total_nonnegative CHECK (total_won >= 0),
 CONSTRAINT buyer_checkout_state_valid CHECK (state IN ('RESERVED', 'PAYMENT_PENDING', 'PAYMENT_UNKNOWN', 'CANCEL_PENDING', 'PAID', 'FAILED', 'CANCELLED', 'EXPIRED')),
 CONSTRAINT buyer_checkout_key_unique UNIQUE (buyer_ref, idempotency_key)
);

ALTER TABLE purchase_order ADD COLUMN checkout_id UUID REFERENCES buyer_checkout(checkout_id);
ALTER TABLE purchase_order ADD CONSTRAINT purchase_order_checkout_source_valid CHECK (
    (order_source = 'CAMPAIGN' AND checkout_id IS NULL) OR
    (order_source = 'CART' AND checkout_id IS NOT NULL)
);
CREATE UNIQUE INDEX purchase_order_checkout_seller_unique ON purchase_order(checkout_id, seller_id)
 WHERE checkout_id IS NOT NULL;

-- A payment belongs to exactly one legacy order or new Buyer checkout.
ALTER TABLE payment_attempt ADD COLUMN checkout_id UUID REFERENCES buyer_checkout(checkout_id);
ALTER TABLE payment_attempt DROP CONSTRAINT payment_attempt_pk;
ALTER TABLE payment_attempt DROP CONSTRAINT payment_attempt_attempt_id_unique;
ALTER TABLE payment_attempt ADD CONSTRAINT payment_attempt_pk PRIMARY KEY (attempt_id);
ALTER TABLE payment_attempt ADD CONSTRAINT payment_attempt_order_id_unique UNIQUE (order_id);
ALTER TABLE payment_attempt ADD CONSTRAINT payment_attempt_checkout_id_unique UNIQUE (checkout_id);
ALTER TABLE payment_attempt ALTER COLUMN order_id DROP NOT NULL;
ALTER TABLE payment_attempt ADD CONSTRAINT payment_attempt_owner_xor
 CHECK ((order_id IS NOT NULL) <> (checkout_id IS NOT NULL));

-- Replace Seller-scoped cart identity/FK with one cart per Buyer.
ALTER TABLE shopping_cart_item DROP CONSTRAINT shopping_cart_item_cart_seller_fk;
ALTER TABLE shopping_cart DROP CONSTRAINT shopping_cart_buyer_seller_unique;
ALTER TABLE shopping_cart DROP CONSTRAINT shopping_cart_id_seller_unique;
ALTER TABLE shopping_cart DROP CONSTRAINT shopping_cart_seller_fk;
ALTER TABLE shopping_cart DROP COLUMN seller_id;
ALTER TABLE shopping_cart ADD CONSTRAINT shopping_cart_buyer_unique UNIQUE (buyer_ref);
ALTER TABLE shopping_cart_item DROP CONSTRAINT shopping_cart_item_cart_sku_unique;
ALTER TABLE shopping_cart_item ADD CONSTRAINT shopping_cart_item_cart_fk
 FOREIGN KEY (cart_id) REFERENCES shopping_cart(cart_id) ON DELETE CASCADE;
ALTER TABLE shopping_cart_item ADD CONSTRAINT shopping_cart_item_cart_seller_sku_unique
 UNIQUE (cart_id, seller_id, sku_id);

-- One gross clearing journal may allocate normalized Seller payables to multiple orders.
ALTER TABLE settlement_journal ADD COLUMN checkout_id UUID REFERENCES buyer_checkout(checkout_id);
ALTER TABLE settlement_journal ALTER COLUMN source_attempt_id DROP NOT NULL;
ALTER TABLE settlement_journal ALTER COLUMN source_order_id DROP NOT NULL;
ALTER TABLE settlement_journal
    DROP CONSTRAINT settlement_journal_exactly_one_source;
ALTER TABLE settlement_journal ADD CONSTRAINT settlement_journal_owner_xor CHECK (
    (source_order_id IS NOT NULL AND checkout_id IS NULL AND source_refund_id IS NULL AND source_attempt_id IS NOT NULL)
    OR (source_order_id IS NULL AND checkout_id IS NOT NULL AND source_refund_id IS NULL AND source_attempt_id IS NOT NULL)
    OR (source_order_id IS NOT NULL AND checkout_id IS NULL AND source_refund_id IS NOT NULL AND source_attempt_id IS NULL)
);
CREATE UNIQUE INDEX settlement_journal_checkout_unique ON settlement_journal(checkout_id)
 WHERE checkout_id IS NOT NULL;
ALTER TABLE settlement_journal ALTER COLUMN seller_id DROP NOT NULL;
ALTER TABLE settlement_entry DROP CONSTRAINT settlement_entry_account_once;
CREATE UNIQUE INDEX settlement_entry_clearing_once ON settlement_entry(journal_id)
 WHERE account_code = 'PSP_CLEARING';
CREATE UNIQUE INDEX settlement_entry_payable_once ON settlement_entry(journal_id) WHERE account_code = 'SELLER_PAYABLE';
CREATE TABLE settlement_seller_allocation (
 journal_id UUID NOT NULL REFERENCES settlement_journal(journal_id),
 order_id UUID NOT NULL REFERENCES purchase_order(order_id),
 seller_id UUID NOT NULL REFERENCES seller(seller_id),
 amount_won BIGINT NOT NULL,
 allocation_ordinal INTEGER NOT NULL,
 CONSTRAINT settlement_seller_allocation_pk PRIMARY KEY (journal_id, order_id),
 CONSTRAINT settlement_seller_allocation_seller_unique UNIQUE (journal_id, seller_id),
 CONSTRAINT settlement_seller_allocation_ordinal_unique UNIQUE (journal_id, allocation_ordinal),
 CONSTRAINT settlement_seller_allocation_amount_positive CHECK (amount_won > 0),
 CONSTRAINT settlement_seller_allocation_ordinal_nonnegative CHECK (allocation_ordinal >= 0)
);
