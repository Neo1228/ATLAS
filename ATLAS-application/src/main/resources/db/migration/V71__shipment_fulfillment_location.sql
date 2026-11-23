-- 여러 location으로 나뉜 주문은 location마다 따로 출고한다. 출고 건이 어느 location의 할당분인지 기록해
-- location별 할당 수량을 넘는 출고를 막는다. 기존 출고는 단일 location 주문이므로 NULL로 둔다.
ALTER TABLE order_shipment ADD COLUMN fulfillment_location_id UUID;
