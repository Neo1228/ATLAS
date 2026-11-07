ALTER TABLE product
    ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    ADD CONSTRAINT product_status_allowed CHECK (status IN ('ACTIVE', 'INACTIVE'));

CREATE TABLE product_channel_availability (
    product_id UUID NOT NULL,
    sales_channel VARCHAR(32) NOT NULL,
    available BOOLEAN NOT NULL,
    CONSTRAINT product_channel_availability_pk PRIMARY KEY (product_id, sales_channel),
    CONSTRAINT product_channel_availability_product_fk FOREIGN KEY (product_id) REFERENCES product (product_id),
    CONSTRAINT product_channel_availability_channel_allowed
        CHECK (sales_channel IN ('ONLINE_STORE', 'CAMPAIGN'))
);

INSERT INTO product_channel_availability (product_id, sales_channel, available)
SELECT product_id, channels.sales_channel, TRUE
FROM product
CROSS JOIN (VALUES ('ONLINE_STORE'), ('CAMPAIGN')) AS channels(sales_channel);
