CREATE INDEX settlement_seller_allocation_seller_journal_idx
    ON settlement_seller_allocation (seller_id, journal_id);
