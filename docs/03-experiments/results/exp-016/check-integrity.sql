WITH selected_checkouts AS (
    SELECT checkout_id, buyer_ref
    FROM buyer_checkout
    WHERE buyer_ref LIKE :'run_id' || '-buyer-%'
),
selected_orders AS (
    SELECT order_id, buyer_ref, checkout_id
    FROM purchase_order
    WHERE buyer_ref LIKE :'run_id' || '-buyer-%'
),
journal_entries AS (
    SELECT journal_id,
           COALESCE(SUM(amount_won) FILTER (WHERE entry_side = 'DEBIT'), 0) AS debit,
           COALESCE(SUM(amount_won) FILTER (WHERE entry_side = 'CREDIT'), 0) AS credit,
           COUNT(*) AS entry_count
    FROM settlement_entry
    GROUP BY journal_id
),
seller_allocations AS (
    SELECT journal_id,
           COALESCE(SUM(amount_won), 0) AS allocated,
           COUNT(*) AS allocation_count
    FROM settlement_seller_allocation
    GROUP BY journal_id
),
selected_journals AS (
    SELECT journal.journal_id,
           journal.gross_amount_won,
           COALESCE(entries.debit, 0) AS debit,
           COALESCE(entries.credit, 0) AS credit,
           COALESCE(entries.entry_count, 0) AS entry_count,
           COALESCE(allocations.allocated, 0) AS allocated,
           COALESCE(allocations.allocation_count, 0) AS allocation_count
    FROM settlement_journal AS journal
    JOIN selected_checkouts AS checkout ON checkout.checkout_id = journal.checkout_id
    LEFT JOIN journal_entries AS entries ON entries.journal_id = journal.journal_id
    LEFT JOIN seller_allocations AS allocations ON allocations.journal_id = journal.journal_id
),
selected_pools AS (
    SELECT *
    FROM inventory_pool
    WHERE sku_id LIKE 'exp016-' || :'run_id' || '-' || :'profile' || '-%'
)
SELECT json_build_object(
    'runId', :'run_id',
    'profile', :'profile',
    'buyerCheckouts', (SELECT COUNT(*) FROM selected_checkouts),
    'sellerOrders', (SELECT COUNT(*) FROM selected_orders),
    'expectedOrderCountMismatches', (
        SELECT COUNT(*)
        FROM (
            SELECT checkout_id
            FROM selected_orders
            GROUP BY checkout_id
            HAVING COUNT(*) <> CASE WHEN :'profile' = 'one-seller' THEN 1 ELSE 2 END
        ) AS mismatches
    ),
    'orderItems', (
        SELECT COUNT(*)
        FROM order_item AS item
        JOIN selected_orders AS selected_order ON selected_order.order_id = item.order_id
    ),
    'duplicateBuyerCheckouts', (
        SELECT COUNT(*)
        FROM (
            SELECT buyer_ref
            FROM selected_checkouts
            GROUP BY buyer_ref
            HAVING COUNT(*) > 1
        ) AS duplicates
    ),
    'paymentAttempts', (
        SELECT COUNT(*)
        FROM payment_attempt AS attempt
        JOIN selected_checkouts AS checkout ON checkout.checkout_id = attempt.checkout_id
    ),
    'capturedPaymentAttempts', (
        SELECT COUNT(*)
        FROM payment_attempt AS attempt
        JOIN selected_checkouts AS checkout ON checkout.checkout_id = attempt.checkout_id
        WHERE attempt.state = 'CAPTURED'
    ),
    'duplicatePaymentAttempts', (
        SELECT COUNT(*)
        FROM (
            SELECT attempt.checkout_id
            FROM payment_attempt AS attempt
            JOIN selected_checkouts AS checkout ON checkout.checkout_id = attempt.checkout_id
            GROUP BY attempt.checkout_id
            HAVING COUNT(*) > 1
        ) AS duplicates
    ),
    'inventoryPools', (SELECT COUNT(*) FROM selected_pools),
    'conservedInventoryPools', (
        SELECT COUNT(*)
        FROM selected_pools
        WHERE available + reserved + claimed = initial_capacity
    ),
    'initialCapacity', (SELECT COALESCE(SUM(initial_capacity), 0) FROM selected_pools),
    'accountedCapacity', (SELECT COALESCE(SUM(available + reserved + claimed), 0) FROM selected_pools),
    'claimedCapacity', (SELECT COALESCE(SUM(claimed), 0) FROM selected_pools),
    'settlementJournals', (SELECT COUNT(*) FROM selected_journals),
    'duplicateSettlementJournals', (
        SELECT COUNT(*)
        FROM (
            SELECT journal.checkout_id
            FROM settlement_journal AS journal
            JOIN selected_checkouts AS checkout ON checkout.checkout_id = journal.checkout_id
            GROUP BY journal.checkout_id
            HAVING COUNT(*) > 1
        ) AS duplicates
    ),
    'journalCountMatchesCheckouts',
        (SELECT COUNT(*) FROM selected_journals) = (SELECT COUNT(*) FROM selected_checkouts),
    'balancedJournals', (
        SELECT COUNT(*)
        FROM selected_journals
        WHERE debit = credit AND entry_count = 2
    ),
    'allocationBalancedJournals', (
        SELECT COUNT(*)
        FROM selected_journals
        WHERE allocated = gross_amount_won
          AND allocation_count = CASE WHEN :'profile' = 'one-seller' THEN 1 ELSE 2 END
    )
)::text AS integrity_summary;
