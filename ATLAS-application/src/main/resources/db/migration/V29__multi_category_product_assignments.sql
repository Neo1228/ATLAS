ALTER TABLE product RENAME COLUMN category_id TO primary_category_id;
ALTER TABLE product RENAME CONSTRAINT product_seller_category_fk TO product_seller_primary_category_fk;
ALTER INDEX product_seller_category_idx RENAME TO product_seller_primary_category_idx;

ALTER TABLE product
    ADD CONSTRAINT product_seller_product_unique UNIQUE (seller_id, product_id);

CREATE TABLE product_category_assignment (
    seller_id UUID NOT NULL,
    product_id UUID NOT NULL,
    category_id UUID NOT NULL,
    CONSTRAINT product_category_assignment_pk PRIMARY KEY (seller_id, product_id, category_id),
    CONSTRAINT product_category_assignment_product_fk
        FOREIGN KEY (seller_id, product_id) REFERENCES product (seller_id, product_id) ON DELETE CASCADE,
    CONSTRAINT product_category_assignment_category_fk
        FOREIGN KEY (seller_id, category_id) REFERENCES product_category (seller_id, category_id)
);

CREATE INDEX product_category_assignment_category_idx
    ON product_category_assignment (seller_id, category_id, product_id);