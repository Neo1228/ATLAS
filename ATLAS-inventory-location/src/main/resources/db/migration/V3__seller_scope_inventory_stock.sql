-- Seller 식별자 도입 전에 생성된 row는 예약된 호환용 식별자로 보존한다.
-- 이 seller UUID를 명시한 요청에서만 조회되며 실제 Seller에게 자동 배정하지 않는다.
-- Legacy seller UUID: 00000000-0000-0000-0000-000000000000
ALTER TABLE inventory_location_stock ADD COLUMN seller_id UUID;
UPDATE inventory_location_stock SET seller_id = '00000000-0000-0000-0000-000000000000' WHERE seller_id IS NULL;
ALTER TABLE inventory_location_stock ALTER COLUMN seller_id SET NOT NULL;
ALTER TABLE inventory_location_stock DROP CONSTRAINT inventory_location_stock_pkey;
ALTER TABLE inventory_location_stock ADD PRIMARY KEY (fulfillment_location_id, seller_id, sku_id);

ALTER TABLE inventory_reservation_item ADD COLUMN seller_id UUID;
UPDATE inventory_reservation_item SET seller_id = '00000000-0000-0000-0000-000000000000' WHERE seller_id IS NULL;
ALTER TABLE inventory_reservation_item ALTER COLUMN seller_id SET NOT NULL;
ALTER TABLE inventory_reservation_item DROP CONSTRAINT inventory_reservation_item_pkey;
ALTER TABLE inventory_reservation_item ADD PRIMARY KEY (reservation_id, seller_id, sku_id);
