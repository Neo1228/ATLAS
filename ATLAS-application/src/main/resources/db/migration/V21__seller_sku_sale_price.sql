ALTER TABLE seller_sku
    ADD COLUMN sale_price_won BIGINT,
    ADD COLUMN sale_starts_at TIMESTAMPTZ,
    ADD COLUMN sale_ends_at TIMESTAMPTZ,
    ADD CONSTRAINT seller_sku_sale_price_window_check CHECK (
        (sale_price_won IS NULL AND sale_starts_at IS NULL AND sale_ends_at IS NULL)
        OR (
            sale_price_won IS NOT NULL
            AND sale_price_won > 0
            AND sale_price_won < price_won
            AND sale_starts_at IS NOT NULL
            AND sale_ends_at IS NOT NULL
            AND sale_starts_at < sale_ends_at
        )
    );
