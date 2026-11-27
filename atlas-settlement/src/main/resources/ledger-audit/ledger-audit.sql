-- 원장 불변식 점검 질의 세트(읽기 전용). 한 문장이 한 점검이고 (check_name, violations) 한 행을 돌려준다.
-- 정상이면 모든 violations 가 0이다. SettlementLedgerAuditRepository 가 이 파일을 읽어 실행하고,
-- 운영자는 같은 파일을 psql -f 로 바로 돌릴 수 있다(백업 복원 뒤, 적용 전 사전 점검).
-- 문장 구분은 세미콜론이므로 주석에는 세미콜론을 쓰지 않는다.

-- 분개마다 차변 합 = 대변 합. capture, 환불, 지급 모두 닫힌 구조다.
SELECT 'JOURNAL_UNBALANCED' AS check_name, count(*) AS violations
FROM (
    SELECT journal_id
    FROM settlement_entry
    GROUP BY journal_id
    HAVING sum(amount_won) FILTER (WHERE entry_side = 'DEBIT')
        IS DISTINCT FROM sum(amount_won) FILTER (WHERE entry_side = 'CREDIT')
) unbalanced;

-- checkout 분개는 판매자별로 항목을 쓰므로 (분개, 판매자) 단위로도 균형이어야 한다.
SELECT 'SELLER_UNBALANCED' AS check_name, count(*) AS violations
FROM (
    SELECT journal_id, seller_id
    FROM settlement_entry
    GROUP BY journal_id, seller_id
    HAVING sum(amount_won) FILTER (WHERE entry_side = 'DEBIT')
        IS DISTINCT FROM sum(amount_won) FILTER (WHERE entry_side = 'CREDIT')
) unbalanced;

-- 항목이 하나도 없는 분개(커밋된 상태에서는 없어야 한다)
SELECT 'JOURNAL_WITHOUT_ENTRIES' AS check_name, count(*) AS violations
FROM settlement_journal j
WHERE NOT EXISTS (SELECT 1 FROM settlement_entry e WHERE e.journal_id = j.journal_id);

-- 분개가 없는 항목(외래 키가 막지만 NOT VALID 이거나 복제 역할로 들어온 행을 찾는다)
SELECT 'ENTRY_WITHOUT_JOURNAL' AS check_name, count(*) AS violations
FROM settlement_entry e
WHERE NOT EXISTS (SELECT 1 FROM settlement_journal j WHERE j.journal_id = e.journal_id);

-- capture 분개: 분개 행에 스냅샷한 총액, 수수료, 보류가 항목 합과 같다.
SELECT 'CAPTURE_SPLIT_MISMATCH' AS check_name, count(*) AS violations
FROM settlement_journal j
CROSS JOIN LATERAL (
    SELECT coalesce(sum(e.amount_won) FILTER (WHERE e.account_code = 'PSP_CLEARING' AND e.entry_side = 'DEBIT'), 0) AS clearing,
           coalesce(sum(e.amount_won) FILTER (WHERE e.account_code = 'PLATFORM_FEE' AND e.entry_side = 'CREDIT'), 0) AS fee,
           coalesce(sum(e.amount_won) FILTER (WHERE e.account_code = 'SELLER_RESERVE' AND e.entry_side = 'CREDIT'), 0) AS reserve
    FROM settlement_entry e
    WHERE e.journal_id = j.journal_id
) s
WHERE j.source_attempt_id IS NOT NULL AND j.checkout_id IS NULL
  AND (s.clearing <> j.gross_amount_won OR s.fee <> j.fee_amount_won OR s.reserve <> j.reserve_amount_won);

-- 환불 분개: PSP_CLEARING 대변 = 환불액
SELECT 'REFUND_CLEARING_MISMATCH' AS check_name, count(*) AS violations
FROM settlement_journal j
WHERE j.source_refund_id IS NOT NULL
  AND coalesce((SELECT sum(e.amount_won) FROM settlement_entry e
                WHERE e.journal_id = j.journal_id AND e.account_code = 'PSP_CLEARING' AND e.entry_side = 'CREDIT'), 0)
      <> j.gross_amount_won;

-- 환불 예약과 환불 분개: POSTED 예약은 같은 금액의 분개가 있고, 분개가 있으면 예약이 POSTED 이다.
SELECT 'REFUND_RESERVATION_MISMATCH' AS check_name, count(*) AS violations
FROM (
    SELECT r.refund_id
    FROM settlement_refund_reservation r
    LEFT JOIN settlement_journal j ON j.source_refund_id = r.refund_id
    WHERE (r.status = 'POSTED' AND (j.journal_id IS NULL OR j.gross_amount_won <> r.amount_won))
       OR (r.status <> 'POSTED' AND j.journal_id IS NOT NULL)
    UNION ALL
    SELECT j.source_refund_id
    FROM settlement_journal j
    WHERE j.source_refund_id IS NOT NULL
      AND NOT EXISTS (SELECT 1 FROM settlement_refund_reservation r WHERE r.refund_id = j.source_refund_id)
) violation;

-- checkout 분개: 판매자 할당 합 = 분개 총액 = PSP_CLEARING 차변
SELECT 'CHECKOUT_ALLOCATION_MISMATCH' AS check_name, count(*) AS violations
FROM settlement_journal j
WHERE j.checkout_id IS NOT NULL
  AND (coalesce((SELECT sum(a.amount_won) FROM settlement_seller_allocation a WHERE a.journal_id = j.journal_id), 0)
           <> j.gross_amount_won
       OR coalesce((SELECT sum(e.amount_won) FROM settlement_entry e
                    WHERE e.journal_id = j.journal_id AND e.account_code = 'PSP_CLEARING' AND e.entry_side = 'DEBIT'), 0)
           <> j.gross_amount_won);

-- 지급: 분개는 CONFIRMED 지급 행과 1:1 이고 금액이 맞다. 대변 PSP_CLEARING = 지급액 + 환율 반올림 + 제공자 수수료
SELECT 'PAYOUT_JOURNAL_MISMATCH' AS check_name, count(*) AS violations
FROM (
    SELECT p.payout_id
    FROM settlement_payout p
    LEFT JOIN settlement_journal j ON j.source_payout_id = p.payout_id
    WHERE (p.state = 'CONFIRMED' AND j.journal_id IS NULL)
       OR (p.state <> 'CONFIRMED' AND j.journal_id IS NOT NULL)
       OR (j.journal_id IS NOT NULL
           AND (j.seller_id <> p.seller_id
                OR j.gross_amount_won <> p.amount_won
                OR coalesce((SELECT sum(e.amount_won) FROM settlement_entry e
                             WHERE e.journal_id = j.journal_id AND e.account_code = 'SELLER_PAYABLE' AND e.entry_side = 'DEBIT'), 0)
                    <> p.amount_won
                OR coalesce((SELECT sum(e.amount_won) FROM settlement_entry e
                             WHERE e.journal_id = j.journal_id AND e.account_code = 'PSP_CLEARING' AND e.entry_side = 'CREDIT'), 0)
                    <> p.amount_won + p.payout_fx_rounding_expense_won + p.provider_fee_won))
) violation;
