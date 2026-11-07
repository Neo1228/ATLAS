SELECT current_database() = 'atlas_query_evidence' AS is_target_db \gset
\if :is_target_db
\else
\quit 3
\endif
SELECT :dataset_size IN (10000, 100000) AS valid_size \gset
\if :valid_size
\else
\quit 3
\endif
SELECT NOT EXISTS (SELECT 1 FROM product WHERE name LIKE 'exp018-%')
   AND NOT EXISTS (SELECT 1 FROM buyer_checkout WHERE buyer_ref LIKE 'exp018-buyer-%')
   AND NOT EXISTS (SELECT 1 FROM seller WHERE seller_id IN (SELECT md5('exp018-seller-'||n)::uuid FROM generate_series(1,100)n))
   AS empty_experiment_db \gset
\if :empty_experiment_db
\else
\quit 3
\endif
BEGIN;
INSERT INTO seller (seller_id) SELECT md5('exp018-seller-'||n)::uuid FROM generate_series(1,100)n;
INSERT INTO product_category (category_id,seller_id,code,name)
SELECT md5('exp018-root-'||n)::uuid,md5('exp018-seller-'||n)::uuid,'EXP018-ROOT','Experiment root'
FROM generate_series(1,100)n;
INSERT INTO product_category (category_id,seller_id,code,name,parent_category_id)
SELECT md5('exp018-child-'||s||'-'||c)::uuid,md5('exp018-seller-'||s)::uuid,
       'EXP018-CHILD-'||lpad(c::text,2,'0'),'Experiment child',md5('exp018-root-'||s)::uuid
FROM generate_series(1,100)s CROSS JOIN generate_series(1,10)c;
INSERT INTO product (product_id,seller_id,category_id,name,description)
SELECT md5('exp018-product-'||n)::uuid,md5('exp018-seller-'||(((n-1)/(:dataset_size/100))::int+1))::uuid,
       md5('exp018-child-'||(((n-1)/(:dataset_size/100))::int+1)||'-'||(((n-1)/(:dataset_size/100/10))%10+1))::uuid,
       CASE WHEN n%100=0 THEN 'exp018-broad-needle-'||n
            WHEN n%10=0 THEN 'exp018-broad-'||n ELSE 'exp018-product-'||n END,'synthetic experiment row'
FROM generate_series(1,:dataset_size)n;
INSERT INTO product_channel_availability(product_id,sales_channel,available)
SELECT product_id,channel,TRUE FROM product CROSS JOIN (VALUES('ONLINE_STORE'),('CAMPAIGN')) c(channel)
WHERE name LIKE 'exp018-%';
INSERT INTO buyer_checkout(checkout_id,buyer_ref,idempotency_key,state,total_won,created_at,updated_at)
SELECT md5('exp018-checkout-'||n)::uuid,'exp018-buyer-'||n,'exp018-key-'||n,'PAID',100,
       TIMESTAMPTZ '2026-01-01 00:00:00+00'+n*INTERVAL '1 second',
       TIMESTAMPTZ '2026-01-01 00:00:00+00'+n*INTERVAL '1 second'
FROM generate_series(1,:dataset_size)n;
INSERT INTO purchase_order(order_id,seller_id,buyer_ref,state,checkout_expires_at,created_at,updated_at,order_source,checkout_key,checkout_id)
SELECT md5('exp018-order-'||n)::uuid,md5('exp018-seller-'||(((n-1)/(:dataset_size/100))::int+1))::uuid,'exp018-buyer-'||n,'PAID',
       TIMESTAMPTZ '2026-01-02 00:00:00+00',TIMESTAMPTZ '2026-01-01 00:00:00+00'+n*INTERVAL '1 second',
       TIMESTAMPTZ '2026-01-01 00:00:00+00'+n*INTERVAL '1 second','CART','exp018-key-'||n,md5('exp018-checkout-'||n)::uuid
FROM generate_series(1,:dataset_size)n;
INSERT INTO settlement_journal(journal_id,source_attempt_id,gross_amount_won,recorded_at,checkout_id)
SELECT md5('exp018-journal-'||n)::uuid,md5('exp018-attempt-'||n)::uuid,100,
       TIMESTAMPTZ '2026-01-01 00:00:00+00'+n*INTERVAL '1 second',md5('exp018-checkout-'||n)::uuid
FROM generate_series(1,:dataset_size)n;
INSERT INTO settlement_seller_allocation(journal_id,order_id,seller_id,amount_won,allocation_ordinal)
SELECT md5('exp018-journal-'||n)::uuid,md5('exp018-order-'||n)::uuid,
       md5('exp018-seller-'||(((n-1)/(:dataset_size/100))::int+1))::uuid,100,0
FROM generate_series(1,:dataset_size)n;
INSERT INTO settlement_entry(journal_id,account_code,entry_side,amount_won,recorded_at)
SELECT md5('exp018-journal-'||n)::uuid,'PSP_CLEARING','DEBIT',100,TIMESTAMPTZ '2026-01-01 00:00:00+00'+n*INTERVAL '1 second'
FROM generate_series(1,:dataset_size)n
UNION ALL
SELECT md5('exp018-journal-'||n)::uuid,'SELLER_PAYABLE','CREDIT',100,TIMESTAMPTZ '2026-01-01 00:00:00+00'+n*INTERVAL '1 second'
FROM generate_series(1,:dataset_size)n;
COMMIT;
ANALYZE seller;
ANALYZE product_category;
ANALYZE product;
ANALYZE product_channel_availability;
ANALYZE buyer_checkout;
ANALYZE purchase_order;
ANALYZE settlement_journal;
ANALYZE settlement_seller_allocation;
ANALYZE settlement_entry;
