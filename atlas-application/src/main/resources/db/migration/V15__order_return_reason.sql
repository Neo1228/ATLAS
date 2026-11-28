-- 구매자 반품 사유와 선택 자유 텍스트(ADR-0110). 판매자 반품 권고가 사유를 근거로 쓴다.
-- 값 집합은 Java enum ReturnReason과 같다(EnumCheckConstraintContractTest). 사유 이전에 만든 행은 NULL로 남는다.
-- 자유 텍스트는 구매자가 쓴 신뢰할 수 없는 데이터라 결정 규칙은 읽지 않는다. 사유 없이 텍스트만 있는 행은 막는다.
-- nullable 컬럼 추가라 기존 행을 다시 쓰지 않고, 이전 애플리케이션은 이 컬럼을 무시하므로 앱만 되돌려도 된다.
-- V13·V14와 독립이다(다른 테이블, 다른 객체).
ALTER TABLE order_return
    ADD COLUMN reason_code varchar(32),
    ADD COLUMN reason_note varchar(500),
    ADD CONSTRAINT order_return_reason_code_check
        CHECK (reason_code IN ('CHANGED_MIND', 'DEFECTIVE', 'DAMAGED_IN_DELIVERY', 'WRONG_ITEM',
            'NOT_AS_DESCRIBED', 'OTHER')),
    ADD CONSTRAINT order_return_reason_note_check
        CHECK (reason_note IS NULL OR (reason_code IS NOT NULL AND char_length(btrim(reason_note)) > 0));
