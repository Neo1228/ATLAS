-- 기존 반품 행은 출처를 알 수 없어 NULL을 허용한다. 신규 반품은 출고 shipment를 기록한다.
ALTER TABLE order_return_item ADD COLUMN shipment_id UUID;
ALTER TABLE order_return_item
    ADD CONSTRAINT order_return_item_shipment_fk
    FOREIGN KEY (shipment_id) REFERENCES order_shipment (shipment_id);
CREATE INDEX idx_order_return_item_shipment ON order_return_item (shipment_id);
CREATE INDEX idx_order_return_item_refund_id ON order_return_item (refund_id) WHERE refund_id IS NOT NULL;
