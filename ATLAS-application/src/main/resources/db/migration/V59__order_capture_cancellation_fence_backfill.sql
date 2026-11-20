-- V58 은 checkout 소유 결제 시도만 소급했다. 주문이 직접 소유한 결제 시도(캠페인 주문)도
-- 배포 시점에 이미 취소 요청·취소된 주문이면 새 capture 를 막는다.
UPDATE payment_attempt p
SET capture_prohibited = true
FROM purchase_order o
WHERE p.order_id = o.order_id
  AND p.checkout_id IS NULL
  AND o.state IN ('CANCEL_PENDING', 'CANCELLED')
  AND p.capture_prohibited = false;
