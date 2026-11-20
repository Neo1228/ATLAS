ALTER TABLE payment_attempt ADD COLUMN capture_prohibited boolean NOT NULL DEFAULT false;

-- 배포 전에 확정된 취소 요청에도 펜스를 적용한다.
UPDATE payment_attempt p
SET capture_prohibited = true
FROM buyer_checkout c
WHERE p.checkout_id = c.checkout_id
  AND c.state IN ('CANCEL_PENDING', 'CANCELLED');
