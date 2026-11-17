-- Global buyer listing cursor/order uses the sign-bit-transformed UUID keys below.
-- This index matches that exact order so the first page can stop after LIMIT rows
-- instead of scanning and top-N sorting every Product. Seller-scoped listings retain
-- product_seller_idx (V7).
CREATE INDEX product_listing_sort_idx ON product (
    (encode(set_byte(set_byte(uuid_send(seller_id), 0,
        get_byte(uuid_send(seller_id), 0) # 128), 8,
        get_byte(uuid_send(seller_id), 8) # 128), 'hex') COLLATE "C"),
    (encode(set_byte(set_byte(uuid_send(product_id), 0,
        get_byte(uuid_send(product_id), 0) # 128), 8,
        get_byte(uuid_send(product_id), 8) # 128), 'hex') COLLATE "C")
);
