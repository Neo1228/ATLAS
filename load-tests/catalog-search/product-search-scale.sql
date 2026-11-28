-- EXP-123 상품 검색 규모 측정 하네스(재사용). 사용 순서:
--   1. V1~ 마이그레이션을 적용한 데이터베이스에 검색 고정 입력
--      (atlas-application/src/test/resources/search/product-search-relevance.sql)과 같은 세션에서 이 파일을 실행한다.
--      psql -v n=300000 -f <고정 입력 파일> -f load-tests/catalog-search/product-search-scale.sql
--      (n = 합성 상품 수. 고정 입력의 임시 테이블을 쓰므로 한 psql 세션에서 두 파일을 잇달아 실행한다)
--   2. VACUUM ANALYZE product; VACUUM ANALYZE product_channel_availability;
--   3. 비교할 검색 SQL(ProductRepository.searchPublic의 문자열)을 bench_variant에 넣고
--      SELECT bench_run('<tag>', '<variant>', 5); 를 변형마다 번갈아 실행한 뒤 SELECT * FROM bench_report('<tag>', 'A0');

-- 라벨 고정 입력 120개를 시험과 같은 UUID로 넣는다(상위 5건을 시험 표와 같은 키로 비교하려고).
INSERT INTO seller (seller_id) VALUES ('11111111-1111-1111-1111-111111111111') ON CONFLICT DO NOTHING;
INSERT INTO product_category (category_id, seller_id, code, name)
VALUES ('22222222-2222-2222-2222-222222222222', '11111111-1111-1111-1111-111111111111', 'all', 'all')
ON CONFLICT DO NOTHING;
INSERT INTO product (product_id, seller_id, name, description, primary_category_id)
SELECT CAST('00000000-0000-0000-0000-' || lpad(substr(product_key, 2), 12, '0') AS uuid),
       '11111111-1111-1111-1111-111111111111', name, description, '22222222-2222-2222-2222-222222222222'
FROM search_fixture_product;
INSERT INTO product_channel_availability (product_id, sales_channel, available)
SELECT CAST('00000000-0000-0000-0000-' || lpad(substr(product_key, 2), 12, '0') AS uuid), 'ONLINE_STORE', TRUE
FROM search_fixture_product;
CREATE TABLE bench_query AS SELECT * FROM search_fixture_query;

-- 합성 카탈로그: 카테고리 어휘로 만든 겹치지 않는 상품 :n 개(psql -v n=...).
-- 설명은 이름과 같은 명사·다른 색·대상·소재·용도를 쓴다. 같은 i는 항상 같은 상품을 만든다(md5 기반 결정적 난수). 분포: 카테고리·브랜드·명사는 u^2(앞쪽 쏠림) 선택,
-- 상품 15%는 영문 이름·설명, 50%는 모델 번호, 4%는 INACTIVE, 5%는 ONLINE_STORE 미노출.
CREATE OR REPLACE FUNCTION pg_temp.u(i bigint, salt text) RETURNS double precision
LANGUAGE sql IMMUTABLE AS $$
    SELECT ('x' || substr(md5(i::text || ':' || salt), 1, 8))::bit(32)::bigint / 4294967296.0
$$;
CREATE OR REPLACE FUNCTION pg_temp.pick(arr text[], x double precision) RETURNS text
LANGUAGE sql IMMUTABLE AS $$ SELECT arr[1 + floor(x * cardinality(arr))::int] $$;

CREATE TEMP TABLE vocab (cat int PRIMARY KEY, ko_nouns text[], en_nouns text[], brands text[], ko_feats text[],
                         en_feats text[]);
INSERT INTO vocab VALUES
(1, '{운동화,러닝화,스니커즈,슬립온,등산화,샌들,슬리퍼,구두,부츠,로퍼,워킹화,축구화}',
    '{sneakers,running shoes,slip-ons,hiking boots,sandals,slippers,loafers,boots,trainers,walking shoes}',
    '{나이키,아디다스,뉴발란스,아식스,푸마,컨버스,반스,휠라,스케쳐스,살로몬,호카,리복}',
    '{쿠션 좋은,가벼운,통기성 좋은,미끄럼 방지,발볼 넓은,방수,경량,논슬립}',
    '{cushioned,lightweight,breathable,non-slip,wide fit,waterproof}'),
(2, '{티셔츠,맨투맨,후드티,셔츠,니트,청바지,슬랙스,원피스,패딩,코트,자켓,레깅스,조거팬츠,가디건,반바지}',
    '{t-shirt,sweatshirt,hoodie,shirt,sweater,jeans,slacks,dress,puffer jacket,coat,jacket,leggings,joggers,cardigan,shorts}',
    '{유니클로,무신사스탠다드,탑텐,스파오,자라,에잇세컨즈,폴로,노스페이스,디스커버리,커버낫,지오다노,빈폴}',
    '{오버핏,슬림핏,기모 안감,구김 없는,신축성 좋은,루즈핏,데일리,빅사이즈}',
    '{oversized,slim fit,fleece lined,wrinkle free,stretch,relaxed fit}'),
(3, '{백팩,크로스백,토트백,숄더백,지갑,카드지갑,파우치,에코백,캐리어,여행가방,힙색,클러치}',
    '{backpack,crossbody bag,tote bag,shoulder bag,wallet,card holder,pouch,suitcase,belt bag}',
    '{쌤소나이트,잔스포츠,허쉘,코치,마이클코어스,닥스,MCM,만다리나덕,투미,루이까또즈,빈폴,질스튜어트}',
    '{수납 많은,가벼운,생활 방수,대용량,미니,슬림,노트북 수납,RFID 차단}',
    '{multi pocket,lightweight,water resistant,large capacity,mini,slim}'),
(4, '{이어폰,헤드폰,스피커,충전기,보조배터리,케이블,키보드,마우스,모니터,웹캠,스마트워치,태블릿,거치대,허브}',
    '{earbuds,headphones,speaker,charger,power bank,cable,keyboard,mouse,monitor,webcam,smartwatch,tablet,stand,hub}',
    '{삼성,애플,LG,소니,앤커,로지텍,샤오미,보스,JBL,벨킨,레노버,큐센,ASUS,브리츠}',
    '{고속 충전,노이즈 캔슬링,무선,블루투스,저소음,휴대용,C타입,대용량}',
    '{fast charging,noise cancelling,wireless,bluetooth,silent,portable,USB-C}'),
(5, '{프라이팬,냄비,도마,칼,텀블러,머그컵,밀폐용기,수저세트,전기포트,에어프라이어,믹서기,커피머신,접시,보온병}',
    '{frying pan,pot,cutting board,knife,tumbler,mug,food container,kettle,air fryer,blender,coffee maker,plate,thermos}',
    '{테팔,해피콜,락앤락,스타벅스,써모스,스탠리,키친아트,쿠쿠,드롱기,필립스,코렐,휘슬러}',
    '{스테인리스,인덕션 겸용,논스틱,보온 보냉,식기세척기 사용 가능,BPA 프리,대용량,세라믹}',
    '{stainless steel,induction ready,nonstick,insulated,dishwasher safe,BPA free}'),
(6, '{선크림,스킨,로션,세럼,크림,클렌징폼,마스크팩,립밤,샴푸,바디워시,핸드크림,쿠션}',
    '{sunscreen,toner,lotion,serum,cream,cleanser,sheet mask,lip balm,shampoo,body wash,hand cream}',
    '{설화수,이니스프리,라운드랩,닥터지,아이소이,토리든,에스트라,라로슈포제,미샤,니베아,아로마티카,해피바스}',
    '{저자극,보습,미백,진정,SPF50,약산성,무향,대용량}',
    '{gentle,hydrating,brightening,soothing,SPF 50,fragrance free}'),
(7, '{텐트,캠핑의자,캠핑테이블,침낭,랜턴,버너,코펠,아이스박스,타프,해먹,매트}',
    '{tent,camping chair,camping table,sleeping bag,lantern,burner,cookware set,cooler,tarp,hammock}',
    '{코베아,콜맨,스노우피크,헬리녹스,네이처하이크,캠프라인,제드,버팔로,노르디스크,지프}',
    '{원터치,경량,방수,접이식,4인용,동계용,휴대용,난연}',
    '{instant,ultralight,waterproof,foldable,4 person,winter}'),
(8, '{요가매트,덤벨,폼롤러,줄넘기,자전거,헬멧,수영복,골프공,테니스라켓,축구공,짐볼,밴드}',
    '{yoga mat,dumbbell,foam roller,jump rope,bicycle,helmet,swimsuit,golf balls,tennis racket,soccer ball}',
    '{나이키,아디다스,윌슨,요넥스,타이틀리스트,미즈노,스피도,아레나,삼천리,르까프}',
    '{미끄럼 방지,두꺼운,초경량,조절 가능한,TPE,고탄성}',
    '{non-slip,thick,ultralight,adjustable,high density}'),
(9, '{의자,책상,책장,침대,매트리스,소파,수납장,행거,조명,커튼,러그,선반}',
    '{chair,desk,bookshelf,bed frame,mattress,sofa,cabinet,clothes rack,lamp,curtain,rug,shelf}',
    '{한샘,이케아,시디즈,일룸,리바트,까사미아,에몬스,데스커,템퍼,시몬스}',
    '{원목,조립식,높이 조절,암막,모듈형,허리 받침,사무용,북유럽풍}',
    '{solid wood,easy assembly,height adjustable,blackout,modular,ergonomic}'),
(10, '{이불,베개,토퍼,담요,수건,침구세트,매트리스커버,쿠션커버}',
    '{comforter,pillow,topper,blanket,towel,bedding set,mattress protector}',
    '{알레르망,이브자리,에이스,까사미아,무인양품,송월타월,한일,코지}',
    '{사계절,구스다운,차렵,극세사,호텔식,항균,겨울용,여름용}',
    '{all season,goose down,microfiber,hotel style,antibacterial}'),
(11, '{볼펜,노트,다이어리,필통,스테이플러,형광펜,연필,포스트잇,파일,계산기}',
    '{pen,notebook,planner,pencil case,stapler,highlighter,pencil,sticky notes,folder,calculator}',
    '{모나미,제브라,파이롯트,스테들러,몰스킨,3M,카시오,플러스,파버카스텔}',
    '{0.5mm,무지,유선,스프링,대용량,휴대용,지워지는}',
    '{0.5mm,dotted,ruled,spiral,refillable}'),
(12, '{사료,간식,장난감,하네스,목줄,배변패드,캣타워,스크래쳐,방석,급식기}',
    '{dog food,treats,toy,harness,leash,pee pads,cat tree,scratcher,pet bed,feeder}',
    '{로얄캐닌,뉴트로,오리젠,아카나,펫모닝,도기맨,캐티맨,하림펫푸드}',
    '{소형견용,대형견용,고양이용,저알러지,유기농,자동,무소음}',
    '{small breed,large breed,for cats,grain free,automatic}');

CREATE TEMP TABLE shared (k text PRIMARY KEY, ko text[], en text[]);
INSERT INTO shared VALUES
('color', '{블랙,화이트,그레이,네이비,베이지,브라운,카키,레드,블루,핑크,검은색,흰색,회색,남색}',
          '{black,white,grey,navy,beige,brown,khaki,red,blue,pink}'),
('who', '{남성,여성,남녀공용,아동,유아,시니어}', '{men,women,unisex,kids,toddler}'),
('material', '{면,가죽,나일론,폴리에스터,울,린넨,실리콘,스테인리스,원목,알루미늄,메쉬,스웨이드}',
             '{cotton,leather,nylon,polyester,wool,linen,silicone,stainless,wood,aluminum,mesh,suede}'),
('use', '{출근,여행,캠핑,운동,데일리,선물,사무실,학교,등산,홈트}', '{commute,travel,camping,workout,everyday,gift,office,school}');

CREATE TEMP TABLE gen AS
WITH base AS (
    SELECT i,
           1 + floor(power(pg_temp.u(i, 'cat'), 1.6) * 12)::int AS cat,
           pg_temp.u(i, 'lang') < 0.15 AS english,
           pg_temp.u(i, 'model') < 0.50 AS has_model
    FROM generate_series(1::bigint, :n) AS i
)
SELECT b.i,
       CASE WHEN b.english THEN
           concat_ws(' ', pg_temp.pick(v.brands, power(pg_temp.u(b.i, 'brand'), 2)),
                     pg_temp.pick(v.en_feats, pg_temp.u(b.i, 'feat')),
                     pg_temp.pick(v.en_nouns, power(pg_temp.u(b.i, 'noun'), 2)),
                     pg_temp.pick(sc.en, pg_temp.u(b.i, 'color')),
                     CASE WHEN b.has_model THEN upper(substr(md5(b.i::text), 1, 2)) || '-' || (100 + b.i % 900) END)
       ELSE
           concat_ws(' ', pg_temp.pick(v.brands, power(pg_temp.u(b.i, 'brand'), 2)),
                     pg_temp.pick(v.ko_feats, pg_temp.u(b.i, 'feat')),
                     pg_temp.pick(v.ko_nouns, power(pg_temp.u(b.i, 'noun'), 2)),
                     pg_temp.pick(sc.ko, pg_temp.u(b.i, 'color')),
                     CASE WHEN b.has_model THEN upper(substr(md5(b.i::text), 1, 2)) || '-' || (100 + b.i % 900) END)
       END AS name,
       CASE WHEN b.english THEN
           format('%s %s for %s. Made of %s, %s. Great for %s.',
                  pg_temp.pick(sc.en, pg_temp.u(b.i, 'color2')), pg_temp.pick(v.en_nouns, power(pg_temp.u(b.i, 'noun'), 2)),
                  pg_temp.pick(sw.en, pg_temp.u(b.i, 'who')), pg_temp.pick(sm.en, pg_temp.u(b.i, 'mat')),
                  pg_temp.pick(v.en_feats, pg_temp.u(b.i, 'feat2')), pg_temp.pick(su.en, pg_temp.u(b.i, 'use')))
       ELSE
           format('%s용 %s %s. %s 소재, %s. %s에 잘 어울려요.',
                  pg_temp.pick(sw.ko, pg_temp.u(b.i, 'who')), pg_temp.pick(sc.ko, pg_temp.u(b.i, 'color2')),
                  pg_temp.pick(v.ko_nouns, power(pg_temp.u(b.i, 'noun'), 2)), pg_temp.pick(sm.ko, pg_temp.u(b.i, 'mat')),
                  pg_temp.pick(v.ko_feats, pg_temp.u(b.i, 'feat2')), pg_temp.pick(su.ko, pg_temp.u(b.i, 'use')))
       END AS description,
       CASE WHEN pg_temp.u(b.i, 'status') < 0.04 THEN 'INACTIVE' ELSE 'ACTIVE' END AS status,
       pg_temp.u(b.i, 'channel') >= 0.05 AS online
FROM base b
JOIN vocab v ON v.cat = b.cat
CROSS JOIN (SELECT ko, en FROM shared WHERE k = 'color') sc
CROSS JOIN (SELECT ko, en FROM shared WHERE k = 'who') sw
CROSS JOIN (SELECT ko, en FROM shared WHERE k = 'material') sm
CROSS JOIN (SELECT ko, en FROM shared WHERE k = 'use') su;

INSERT INTO product (product_id, seller_id, name, description, primary_category_id, status)
SELECT CAST(md5('catalog:' || i) AS uuid), '11111111-1111-1111-1111-111111111111', name, description,
       '22222222-2222-2222-2222-222222222222', status
FROM gen;
INSERT INTO product_channel_availability (product_id, sales_channel, available)
SELECT CAST(md5('catalog:' || i) AS uuid), 'ONLINE_STORE', TRUE FROM gen WHERE online;

-- 측정 함수. 변형 SQL(:query 등 명명 매개변수)을 $1..$5로 바꿔 EXPLAIN (ANALYZE, BUFFERS)로 잰다.
CREATE TABLE IF NOT EXISTS bench_variant (variant text PRIMARY KEY, sql text NOT NULL);
CREATE TABLE IF NOT EXISTS bench_result (
    tag text, variant text, query_id int, run int, exec_ms double precision, plan_ms double precision,
    shared_hit bigint, shared_read bigint, candidates bigint
);
CREATE TABLE IF NOT EXISTS bench_top (tag text, variant text, query_id int, top text[], PRIMARY KEY (tag, variant, query_id));

CREATE OR REPLACE FUNCTION bench_sql(v text) RETURNS text LANGUAGE sql AS $$
    SELECT replace(replace(replace(replace(replace(sql, ':query', '$1'), ':minPriceWon', '$2'), ':maxPriceWon', '$3'),
                   ':asOf', '$4'), ':limit', '$5')
    FROM bench_variant WHERE variant = v
$$;

-- 후보 수: product를 읽은 노드가 힙에서 가져온 행 수(남긴 행 + 필터·재검사로 버린 행).
CREATE OR REPLACE FUNCTION bench_candidates(node jsonb) RETURNS bigint LANGUAGE plpgsql AS $$
DECLARE
    child jsonb;
    best bigint := 0;
BEGIN
    IF node->>'Relation Name' = 'product' THEN
        RETURN round((COALESCE((node->>'Actual Rows')::numeric, 0) + COALESCE((node->>'Rows Removed by Filter')::numeric, 0)
            + COALESCE((node->>'Rows Removed by Index Recheck')::numeric, 0)) * COALESCE((node->>'Actual Loops')::numeric, 1))::bigint;
    END IF;
    FOR child IN SELECT * FROM jsonb_array_elements(COALESCE(node->'Plans', '[]')) LOOP
        best := greatest(best, bench_candidates(child));
    END LOOP;
    RETURN best;
END $$;

CREATE OR REPLACE FUNCTION bench_run(tag_in text, v text, runs int) RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    q record;
    s text := bench_sql(v);
    j jsonb;
    t text[];
    r int;
BEGIN
    FOR q IN SELECT query_id, query FROM bench_query ORDER BY query_id LOOP
        EXECUTE format('SELECT array_agg(right(CAST(product_id AS text), 12)) FROM (%s) x', s)
            INTO t USING q.query, NULL::bigint, NULL::bigint, now(), 5;
        INSERT INTO bench_top VALUES (tag_in, v, q.query_id, COALESCE(t, '{}'))
            ON CONFLICT (tag, variant, query_id) DO UPDATE SET top = EXCLUDED.top;
        FOR r IN 1..runs LOOP
            EXECUTE 'EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) ' || s
                INTO j USING q.query, NULL::bigint, NULL::bigint, now(), 5;
            INSERT INTO bench_result VALUES (tag_in, v, q.query_id, r, (j->0->>'Execution Time')::float8,
                (j->0->>'Planning Time')::float8, (j->0->'Plan'->>'Shared Hit Blocks')::bigint,
                (j->0->'Plan'->>'Shared Read Blocks')::bigint, bench_candidates(j->0->'Plan'));
        END LOOP;
    END LOOP;
END $$;

-- 요약: 변형별 상위 5건이 기준 변형과 같은 질의 수, 실행 시간 p50/p95, 공유 버퍼, product에서 읽은 행 수.
CREATE OR REPLACE FUNCTION bench_report(tag_in text, baseline text)
RETURNS TABLE (variant text, same_top5 bigint, p50_ms numeric, p95_ms numeric, mean_buffers bigint,
               mean_rows_read bigint, max_rows_read bigint, mean_plan_ms numeric)
LANGUAGE sql AS $$
    SELECT r.variant,
           (SELECT count(*) FROM bench_top t JOIN bench_top b ON b.tag = t.tag AND b.query_id = t.query_id
            WHERE t.tag = tag_in AND t.variant = r.variant AND b.variant = baseline AND t.top = b.top),
           round(CAST(percentile_cont(0.5) WITHIN GROUP (ORDER BY r.exec_ms) AS numeric), 1),
           round(CAST(percentile_cont(0.95) WITHIN GROUP (ORDER BY r.exec_ms) AS numeric), 1),
           CAST(avg(r.shared_hit + r.shared_read) AS bigint), CAST(avg(r.candidates) AS bigint), max(r.candidates),
           round(CAST(avg(r.plan_ms) AS numeric), 2)
    FROM bench_result r WHERE r.tag = tag_in GROUP BY r.variant ORDER BY r.variant
$$;
