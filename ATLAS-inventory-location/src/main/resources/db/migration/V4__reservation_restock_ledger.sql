-- 판매 확정(CLAIMED) 재고를 환불·반품 검수 통과 수량만큼 판매 가능 수량으로 되돌린 기록.
-- 같은 restock key는 한 번만 적용하고, reservation item 수량을 넘겨 되돌리지 못하게 누적량을 이 테이블로 계산한다.
CREATE TABLE inventory_reservation_restock (
    restock_key VARCHAR(128) NOT NULL,
    reservation_id UUID NOT NULL REFERENCES inventory_reservation (reservation_id),
    seller_id UUID NOT NULL,
    sku_id VARCHAR(96) NOT NULL,
    quantity INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (restock_key, seller_id, sku_id),
    CONSTRAINT inventory_reservation_restock_quantity CHECK (quantity > 0)
);

CREATE INDEX inventory_reservation_restock_reservation_idx
    ON inventory_reservation_restock (reservation_id, seller_id, sku_id);
