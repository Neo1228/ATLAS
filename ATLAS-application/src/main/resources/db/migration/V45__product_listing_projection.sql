-- 목록 조회용 읽기 모델(projection).
--
-- 왜: 목록 한 페이지(100건)를 만들 때 현재는 product + seller_sku + 채널 + 카테고리를
-- 세 쿼리로 읽어 buffer 1,132 를 사용한다. 이 테이블로 채널·카테고리를 미리 집계하면
-- 같은 페이지가 buffer 103, 쿼리 1회로 끝난다(로컬 2M 상품 실측).
--
-- 정합성 계약: 목록은 준실시간이면 충분하다. 상세 조회와 주문 가능 판정은 원본
-- (product, seller_sku, product_channel_availability)을 계속 본다. 즉 이 표가 낡아도
-- 결제·재고 판정에는 영향이 없다.
--
-- 갱신: 카탈로그 쓰기 트랜잭션에서 같은 트랜잭션으로 갱신한다. 부하 중
-- product/seller_sku 의 n_tup_upd 가 0 이었을 만큼 상품 변경이 드물어 부담이 작다.
-- 트리거를 쓰지 않는 이유: 애플리케이션 동작이 DB 에 숨는 것을 피한다.

CREATE TABLE product_listing_projection (
    product_id UUID PRIMARY KEY REFERENCES product (product_id) ON DELETE CASCADE,
    seller_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL,
    primary_category_id UUID,
    -- 판매 가능 채널만 담는다. 값이 없으면 빈 배열이며 NULL 이 아니다.
    channels TEXT[] NOT NULL DEFAULT '{}',
    -- 추가 카테고리. 대표 카테고리는 primary_category_id 에 따로 있다.
    categories UUID[] NOT NULL DEFAULT '{}',
    updated_at TIMESTAMPTZ NOT NULL
);

-- 목록의 실제 접근 패턴: ACTIVE 만, 정렬 키 순.
-- 판매자 범위 조회도 같은 인덱스의 선두 컬럼으로 처리한다.
CREATE INDEX ix_product_listing_projection_active
    ON product_listing_projection (seller_id, product_id)
    WHERE status = 'ACTIVE';

-- 기존 상품을 채워 넣는다. 배포 시 한 번 실행되며, 이후는 쓰기 경로가 갱신한다.
INSERT INTO product_listing_projection (
    product_id, seller_id, status, primary_category_id, channels, categories, updated_at
)
SELECT p.product_id,
       p.seller_id,
       p.status,
       p.primary_category_id,
       COALESCE((SELECT array_agg(c.sales_channel ORDER BY c.ctid)
                 FROM product_channel_availability c
                 WHERE c.product_id = p.product_id AND c.available), '{}'),
       COALESCE((SELECT array_agg(a.category_id ORDER BY a.category_id)
                 FROM product_category_assignment a
                 WHERE a.product_id = p.product_id), '{}'),
       CURRENT_TIMESTAMP
FROM product p;
