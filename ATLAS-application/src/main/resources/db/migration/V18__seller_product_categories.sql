CREATE TABLE product_category (
    category_id UUID NOT NULL DEFAULT uuidv7(),
    seller_id UUID NOT NULL,
    code VARCHAR(128) NOT NULL,
    name TEXT NOT NULL,
    CONSTRAINT product_category_pk PRIMARY KEY (category_id),
    CONSTRAINT product_category_seller_fk FOREIGN KEY (seller_id) REFERENCES seller (seller_id),
    CONSTRAINT product_category_seller_code_unique UNIQUE (seller_id, code),
    CONSTRAINT product_category_seller_category_unique UNIQUE (seller_id, category_id),
    CONSTRAINT product_category_code_nonblank CHECK (length(trim(code)) > 0),
    CONSTRAINT product_category_name_nonblank CHECK (length(trim(name)) > 0)
);

INSERT INTO product_category (seller_id, code, name)
SELECT seller_id, 'UNCATEGORIZED', '미분류'
FROM seller;

ALTER TABLE product ADD COLUMN category_id UUID;
UPDATE product AS p
SET category_id = c.category_id
FROM product_category AS c
WHERE c.seller_id = p.seller_id
  AND c.code = 'UNCATEGORIZED';

ALTER TABLE product
    ALTER COLUMN category_id SET NOT NULL,
    ADD CONSTRAINT product_seller_category_fk FOREIGN KEY (seller_id, category_id)
        REFERENCES product_category (seller_id, category_id);
CREATE INDEX product_seller_category_idx ON product (seller_id, category_id, product_id);
