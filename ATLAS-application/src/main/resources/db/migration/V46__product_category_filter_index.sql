-- 카테고리 필터 목록에 인덱스를 추가한다.
--
-- 왜: 목록에서 카테고리 조건을 쓰면 primary_category_id 인덱스가 없어
-- 정렬 인덱스를 따라가며 필터한다. 실측(2M 상품)에서 buffer 5,798 hit +
-- 3,136 read = 8,934 로, 필터 없는 목록(503)의 18배였다.
--
-- 가장 흔한 형태는 (ACTIVE, 대표 카테고리) 이므로 복합 부분 인덱스를 둔다.
-- 정렬 키를 두 번째 컬럼에 넣어 ORDER BY 도 인덱스로 처리되게 한다.
-- product 의 정렬 계약은 expression index 와 같고, 여기서는 UUID 컬럼 순서로
-- 근사하지 않고 실제 정렬 표현식을 그대로 쓴다.
CREATE INDEX ix_product_primary_category_listing
    ON product (
        primary_category_id,
        encode(set_byte(set_byte(uuid_send(product_id), 0,
            get_byte(uuid_send(product_id), 0) # 128), 8,
            get_byte(uuid_send(product_id), 8) # 128), 'hex') COLLATE "C"
    )
    WHERE status = 'ACTIVE';
