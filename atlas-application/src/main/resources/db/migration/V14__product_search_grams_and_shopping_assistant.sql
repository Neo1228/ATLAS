-- EXP-120, ADR-0109: 상품 검색(n-gram)과 쇼핑 비서 상태.

-- 1. 검색 n-gram
--
-- 한글 음절 2-gram과 영숫자 3-gram을 만든다. pg_trgm과 전문 검색 대신 이 함수를 쓰는 이유는
-- 데이터베이스 LC_CTYPE과 무관하게 같은 결과를 내기 위해서다. pg_trgm은 LC_CTYPE이 C이면
-- 한글을 단어 문자로 보지 않아 show_trgm('검은색 운동화를')이 빈 배열이다(EXP-120).
-- 문자 범위([가-힣], [0-9a-z])는 PostgreSQL 정규식이 코드 포인트로 비교하므로 ctype에 의존하지 않는다.
-- lower()는 ctype에 따라 비ASCII 대소문자 처리가 다르지만, 결과에서 ASCII 영숫자와 한글 음절만 남기므로
-- 두 ctype에서 같은 배열이 나온다. 이 전제는 ProductSearchRelevanceIntegrationTest가 두 ctype으로 확인한다.
CREATE FUNCTION catalog_search_grams(input text) RETURNS text[]
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT COALESCE(array_agg(DISTINCT substr(r.padded, i, r.n)), '{}')
    FROM (
        SELECT ' ' || m[1] || ' ' AS padded, CASE WHEN m[1] ~ '^[가-힣]' THEN 2 ELSE 3 END AS n
        FROM regexp_matches(lower(COALESCE(input, '')), '([가-힣]+|[0-9a-z]+)', 'g') AS m
    ) r
    CROSS JOIN LATERAL generate_series(1, length(r.padded) - r.n + 1) AS i
$$;

-- 검색어 정규화: 3음절 이상 한글 어절 끝의 조사 하나를 떼고 요청 동사를 뺀다("운동화를" → "운동화",
-- "패딩을 사고 싶어" → "패딩"). 상품 쪽에는 적용하지 않는다(설명문 속 조사는 n-gram 겹침이 흡수한다).
CREATE FUNCTION catalog_search_terms(input text) RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT COALESCE(string_agg(t, ' ' ORDER BY ord), '')
    FROM (
        SELECT CASE WHEN char_length(w) > 2
                    THEN regexp_replace(w, '(으로|에서|에게|하고|을|를|이|가|은|는|로|에|도|와|과|랑)$', '')
                    ELSE w END AS t, ord
        FROM regexp_split_to_table(lower(COALESCE(input, '')), '[^0-9a-z가-힣]+') WITH ORDINALITY AS s(w, ord)
        WHERE w <> '' AND w NOT IN ('찾아줘', '찾아', '찾고', '사고', '싶어', '필요해', '필요', '추천', '추천해줘',
                                    '주세요', '보여줘', '있어', '있나요', '좀', '사려고', '해줘')
    ) x
$$;

-- 생성 열은 상품을 쓰는 모든 경로(JPA, 시드 SQL)에서 같은 규칙으로 갱신된다.
-- 추가 시 product 테이블을 다시 쓰므로 대량 데이터 환경에서는 배포 창에 실행한다(EXP-120 측정 참고).
ALTER TABLE product
    ADD COLUMN search_name_grams text[] GENERATED ALWAYS AS (catalog_search_grams(name)) STORED,
    ADD COLUMN search_grams text[]
        GENERATED ALWAYS AS (catalog_search_grams(name || ' ' || COALESCE(description, ''))) STORED;

CREATE INDEX product_search_grams_idx ON product USING gin (search_grams);

-- 2. 쇼핑 비서
--
-- 구매자가 직접 정한 지출 상한. LLM 도구에는 이 값을 쓰는 도구가 없다(구매자 HTTP 경로만 쓴다).
CREATE TABLE assistant_spending_cap (
    buyer_ref character varying(256) PRIMARY KEY,
    cap_won bigint NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT assistant_spending_cap_positive CHECK (cap_won > 0)
);

-- 대화 기록은 서버가 소유한다. 클라이언트가 이전 도구 결과(가격)를 위조해 보낼 수 없게 하고,
-- 대화별 토큰 상한을 클라이언트가 초기화하지 못하게 하려는 것이다.
CREATE TABLE assistant_conversation (
    conversation_id uuid PRIMARY KEY,
    buyer_ref character varying(256) NOT NULL,
    tokens_used bigint NOT NULL DEFAULT 0,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT assistant_conversation_tokens_nonnegative CHECK (tokens_used >= 0)
);

CREATE INDEX assistant_conversation_buyer_idx ON assistant_conversation (buyer_ref, updated_at DESC);

CREATE TABLE assistant_message (
    conversation_id uuid NOT NULL REFERENCES assistant_conversation (conversation_id) ON DELETE CASCADE,
    seq integer NOT NULL,
    role character varying(16) NOT NULL,
    content text,
    tool_calls text,
    tool_call_id character varying(128),
    created_at timestamp with time zone NOT NULL,
    PRIMARY KEY (conversation_id, seq),
    CONSTRAINT assistant_message_role_allowed CHECK (role IN ('USER', 'ASSISTANT', 'TOOL'))
);
