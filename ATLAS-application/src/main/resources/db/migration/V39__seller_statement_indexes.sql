-- Seller 정산 명세는 seller_id + recorded_at 범위로 settlement_journal을 3개 branch로 조회한다.
-- 기존 index는 source_attempt_id·source_refund_id·checkout_id만 선두로 두므로
-- seller/기간 필터가 항상 전체 ledger scan이었다. (#297)
--
-- settlement_seller_allocation (seller_id, journal_id)는 V28에 이미 존재하므로
-- 여기서 다시 만들지 않는다.
CREATE INDEX settlement_journal_seller_recorded_idx
    ON settlement_journal (seller_id, recorded_at, journal_id);
