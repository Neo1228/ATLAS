-- 판매자 반품 대기열의 seller/requested_at/return_id keyset 순서를 지원한다.
CREATE INDEX ix_order_return_seller_history
    ON order_return (seller_id, requested_at DESC, return_id DESC);
