-- V65 이전 workflow item에는 Seller 식별자가 없었다. 기존 intent는 예약된 legacy seller ID로 보존한다.
-- 신규 요청은 항상 실제 Seller SKU 소유자를 저장한다.
ALTER TABLE order_inventory_location_item ADD COLUMN seller_id UUID;
UPDATE order_inventory_location_item
SET seller_id = '00000000-0000-0000-0000-000000000000'
WHERE seller_id IS NULL;
ALTER TABLE order_inventory_location_item ALTER COLUMN seller_id SET NOT NULL;

ALTER TABLE order_inventory_location_item
    DROP CONSTRAINT order_inventory_location_item_pkey;
ALTER TABLE order_inventory_location_item
    ADD CONSTRAINT order_inventory_location_item_pkey
    PRIMARY KEY (workflow_id, fulfillment_location_id, seller_id, sku_id);
