-- The admission expiry sweep keyset-pages by (expires_at, ticket_id).
-- Add the ticket ID as a tie-breaker to the existing expiry partial index so both
-- the first page and tuple cursor seek avoid a sort. Seller/SKU are fetched from heap
-- for the bounded candidate window instead of inflating this high-churn index.
DROP INDEX admission_ticket_expiry_idx;

CREATE INDEX admission_ticket_expiry_idx
    ON admission_ticket (expires_at, ticket_id)
    WHERE status IN ('WAITING', 'CHECKOUT_ACTIVE');
