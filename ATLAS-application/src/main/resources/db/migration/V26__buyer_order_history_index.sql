CREATE INDEX purchase_order_buyer_history_idx
    ON purchase_order (buyer_ref, created_at DESC, order_id DESC);
