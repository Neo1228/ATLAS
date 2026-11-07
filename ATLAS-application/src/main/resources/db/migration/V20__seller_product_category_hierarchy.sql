ALTER TABLE product_category
    ADD COLUMN parent_category_id UUID;

ALTER TABLE product_category
    ADD CONSTRAINT product_category_not_self_parent
        CHECK (parent_category_id IS NULL OR parent_category_id <> category_id),
    ADD CONSTRAINT product_category_parent_seller_fk
        FOREIGN KEY (seller_id, parent_category_id)
        REFERENCES product_category (seller_id, category_id);
