-- 판매자 주문 목록의 keyset 페이지네이션을 지원한다.
--
-- 왜 필요한가: 지금까지 판매자 주문 조회 API 가 없었다. 모든 이행 경로가
-- /sellers/{sellerId}/orders/{orderId}/... 형태인데, 판매자가 자기 orderId 를 알 방법이
-- API 에 없어 사실상 이행을 시작할 수 없었다. 구매자 이력과 같은 keyset 방식을 쓰려면
-- (seller_id, created_at desc, order_id desc) 정렬을 인덱스가 받쳐야 한다.
--
-- 기존 인덱스는 (seller_id, buyer_ref) partial 이라 판매자 전체 목록 정렬에 쓰이지 못한다.
CREATE INDEX IF NOT EXISTS purchase_order_seller_history_idx
    ON purchase_order (seller_id, created_at DESC, order_id DESC);
