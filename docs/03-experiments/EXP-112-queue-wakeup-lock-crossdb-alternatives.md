# EXP-112: 큐 레이아웃·깨우기·락·두 DB 원자성: 현행 대 대안

- 상태: 사전 등록 커밋(측정 전). 결과 절은 측정 뒤에 채운다.
- 날짜: 2026-11-27
- 관련: [#715](https://github.com/Neo1228/ATLAS/issues/715), [EXP-019](EXP-019-inventory-batch-lock-order.md), [EXP-042](EXP-042-db-concurrency-guard-audit.md), [EXP-055](EXP-055-stock-lock-strategy-comparison.md), [ADR-0038](../02-decisions/adr/ADR-0038-durable-payment-recovery-workflow.md), [ADR-0059](../02-decisions/adr/ADR-0059-independent-inventory-location-service.md), [ADR-0060](../02-decisions/adr/ADR-0060-order-inventory-reservation-workflow.md)

## 문제 한 문장

큐 테이블의 갱신이 인덱스 열을 바꿔 HOT를 못 쓰고 vacuum과 겹치는지, 1초 폴링이 불필요한 지연과 유휴 질의를 만드는지, advisory 락 키 구성과 락 순서가 충돌·교착을 만들 수 있는지, 두 DB 사이를 상태 전이로 잇는 방식이 2PC보다 실제로 나쁜지를 현행 코드·스키마에서 수치로 가른다. 수치가 지지하지 않으면 "현행 유지"를 수치와 함께 닫는다.

## 공통 조건 (실행 전 고정)

- 서버: `postgres:18.6` 컨테이너, 설정은 공식 이미지 기본값(앱 compose와 같다). 스키마는 `V1~V6` 그대로(order DB), location DB는 `V1~V4`.
- 측정 칸은 호스트 잠금(`measure-lock.sh`) 아래서만 돌리고 run마다 `measure-lock.sh status`(load1, 다른 라벨 컨테이너)를 기록한다. **오염 run 정의**: run 시작 또는 끝 status에서 load1 > 12이거나 `atlasexp=queuetx`가 아닌 라벨 컨테이너가 있으면 오염이며 본표에서 빼고 별도 표에 적는다. 정확성(통과·실패, 건수, 불변식) 칸은 부하와 무관해 `nice -n 19`로 한 번에 하나씩 돌린다.
- 시간 값은 맥락이고 결론은 건수·비율·불변식으로 낸다. 비율 비교는 run 3개의 [min, max]가 겹치지 않을 때만 "차이 있음"이라 쓴다.
- 원자료와 구동 스크립트는 커밋하지 않는다. 문서의 "삭제한 파일의 내용"에 매개변수·방법·정확한 SQL·집계 열을 옮긴다.

## 영역 1: 큐 테이블 레이아웃 (E1)

### 배경과 사실 확인 대상

- 대상 큐 5개: `payment_recovery_job`, `settlement_payout_dispatch_outbox`, `paypal_webhook_retry`, `order_inventory_reservation_workflow`, `order_inventory_event_cursor`.
- 코드 읽기에서 얻은 것(측정 아님): 결제 복구 worker는 poll마다 `claimNextDue(1, ...)`를 최대 `batch-size`(10)번 호출해 일감을 하나씩 청구한다. 청구 문장은 `status`와 `available_at`을 바꾸지 않고 `lease_token`, `lease_until`, `attempt_count`, `last_attempt_at`, `updated_at`만 바꾼다. 상태 전이(`markForApplication`은 `available_at`을, `markApplied`·`complete`는 `status`를 바꾼다)와 재예약(`available_at`)은 부분 인덱스의 키나 조건 열을 바꾼다. 따라서 청구는 HOT 가능성이 있고(조사 문서의 "청구 0%"는 상태를 바꾸는 가상 청구였다) 전이·재예약은 불가능하다고 예상한다.

### 워크로드 (정상 상태, 팔마다 같은 시드)

- 한 PostgreSQL 인스턴스에 다섯 큐를 동시에 구동한다. 각 큐의 생애는 실제 SQL(저장소의 문장)을 그대로 쓴다. 도착은 포아송.
  - `payment_recovery_job`: 결제 60건/s. 건마다 INSERT → `markForApplication` → `markApplied`(정상 경로, 요청 안). 3%는 정상 경로를 건너뛰고 worker가 청구한다(청구 → 처리 200 ms → `markForApplication` → 재청구 → `complete`, 이 중 20%는 `reschedule` 후 재청구). worker 2개, 1초 폴링, poll당 최대 10청구, 매 poll `expireExhausted` 실행. 과거 이력 완료 행 50만 개(대응하는 `payment_attempt` 50만 행 포함)를 미리 넣는다.
  - `paypal_webhook_retry`: 5건/s, 청구 후 30%는 재예약, 70%는 종료. 워커 1초 폴링.
  - `order_inventory_reservation_workflow`: 30건/s, PENDING → 청구 → RESERVED → CLAIMED 전이.
  - `settlement_payout_dispatch_outbox`: 2건/s, 청구 → 증거 기록 → WAITING_PROVIDER 또는 COMPLETED, worker 5초 폴링. 과거 이력 완료 행 10만 개를 미리 넣는다.
  - `order_inventory_event_cursor`: 소비자 그룹 1행을 0.2초마다 청구·갱신(커서 전진과 임대 갱신).
- 자동 vacuum은 켜 둔 기본값(`autovacuum_vacuum_scale_factor=0.2`, 임계 50, `naptime=1min`). 팔 A2·A5만 테이블별 값을 바꾼다. 한 run 10분(워밍업 60초 제외), 같은 팔의 3 run을 동시에 돌리고 각각 별도 컨테이너(CPU 2개로 제한)를 쓴다.

### 팔

| 팔 | 내용 |
|---|---|
| A0 | 현행 스키마 |
| A1 | 큐 5개 `fillfactor=75`(인덱스는 기본값) |
| A2 | 큐 5개 테이블별 자동 vacuum(`scale_factor=0.02`, 임계 50, `cost_limit` 기본 유지)만 변경 |
| A3 | `payment_recovery_job`만 임대·시도 열을 별도 작은 테이블(`payment_recovery_job_lease`, 기본키만, `fillfactor=70`)로 분리(재설계). 청구가 작은 테이블만 갱신하도록 청구·완료·재예약 SQL을 바꾼 시제품 |
| A4 | A0 스키마에서 청구만 변형: poll당 일감 하나씩 10번 대신 한 문장 `UPDATE ... WHERE id IN (SELECT ... FOR UPDATE SKIP LOCKED LIMIT 10)` |
| A5 | A0 스키마 + 부분 인덱스 2개: `payment_recovery_job (recovery_job_id) WHERE status = 'WAITING_FOR_BUYER'`, `settlement_payout_dispatch_outbox (lease_until) WHERE status = 'LEASED'` |

### 측정 지표 (run마다, 테이블별)

`pg_stat_user_tables`의 `n_tup_upd`, `n_tup_hot_upd`, `n_live_tup`, `n_dead_tup`, `autovacuum_count`, `autovacuum_vacuum` 소요 시간(로그), 테이블·인덱스 크기 증가(`pg_relation_size`), 총 WAL 바이트(`pg_current_wal_lsn` 차와 `pg_stat_wal`), 완료 일감당 WAL, 청구 호출 지연 p50/p95/p99(클라이언트 측정, 호출당), 생성→청구 대기 시간(`now - available_at`), 처리량(청구/s, 완료/s), 호스트 load1.

### 가설과 반증 조건 (실행 전 등록, 실행 뒤에 바꾸지 않는다)

| # | 가설 | 반증(발동) 조건 | 지표 | 발동 시 |
|---|---|---|---|---|
| Q1 | A0의 `payment_recovery_job` HOT 비율(`n_tup_hot_upd/n_tup_upd`)은 30% 미만이다(정상 경로의 두 갱신이 인덱스 열을 바꾸므로) | 3 run 중 하나라도 30% 이상 | 위 지표 | 조사 문서의 HOT 가설 서술을 정정 |
| Q2 | A0에서 청구 문장 단독의 HOT 비율은 50% 이상이다(인덱스 열을 안 바꾼다) | 50% 미만 | 별도 통계 뷰: 청구만 수행한 구간의 `pg_stat_xact_user_tables` | 청구는 fillfactor 없이는 HOT가 안 된다고 정정 |
| Q3 | A1은 A0보다 `payment_recovery_job` HOT 비율이 20%p 이상 높고 완료 일감당 WAL이 15% 이상 작다 | 3 run의 [min,max]가 A0와 겹치거나 차이가 기준 미만 | HOT 비율, WAL/일감 | A1은 채택하지 않는다 |
| Q4 | A2는 청구 p99를 A0보다 20% 이상 낮춘다 | 겹침 또는 20% 미만 | p99 | autovacuum 튜닝은 채택하지 않는다 |
| Q5 | A3는 청구·완료 경로의 `payment_recovery_job` 본표 갱신을 청구에서 없애 WAL/일감을 30% 이상 줄이고 청구 p50은 A0 대비 20% 이하만 늘린다 | WAL 감소 30% 미만 또는 p50 증가 20% 초과 | WAL/일감, p50 | 분리는 채택하지 않는다 |
| Q6 | A4는 일감당 질의 수를 5배 이상 줄이고 청구 처리량을 A0 이상으로 유지한다 | 질의 수 감소 5배 미만 또는 처리량 감소 | `pg_stat_statements` calls, 처리량 | 변형은 채택하지 않는다 |
| Q7 | 어떤 팔도 A0 대비 p99 청구 지연이 10% 넘게 나빠지지 않는다(회귀 가드) | 10% 초과 악화 | p99 | 해당 팔 기각 |
| Q8 | 이력 완료 행 50만 개 + 정상 상태 10분에서 A0의 `n_dead_tup`는 자동 vacuum으로 `payment_recovery_job` 라이브 행의 20% 이하로 유지된다 | 20% 초과 | `n_dead_tup/n_live_tup` | 큐 vacuum 튜닝 필요로 정정 |

#### 추가 사전 등록 (영역 1, 측정 전에 커밋)

아래 Q9~Q11은 첫 사전 등록 커밋 뒤, 측정 전 탐색 중 `EXPLAIN`에서 먼저 눈에 띈 현상을 확인하려고 추가했다(사전 등록 밖에서 발견한 것이라 "탐색 발견"으로 구분한다). 탐색 관찰(n=1, 가설 검증이 아님): 결제 복구 worker가 poll마다 호출하는 `resumeExpiredApprovalWaits` 질의가 이력 50만 행의 `payment_recovery_job`을 순차 스캔했고(`WAITING_FOR_BUYER` 행을 위한 인덱스가 없다), 지급 worker의 `claim` 질의는 `OR`로 `LEASED`를 함께 조회해 부분 인덱스를 못 쓰고 `settlement_payout_dispatch_outbox`를 순차 스캔했다.

| # | 가설 | 반증(발동) 조건 | 지표 | 발동 시 |
|---|---|---|---|---|
| Q9 | 반복 실행되는 질의 중 이력 행 수에 비례해 읽는 블록이 늘어나는 것이 있다: 실제 앱을 이력 시드(결제 복구 50만, 지급 25만, workflow 10만 행)에 붙여 60초 유휴로 둘 때 `pg_stat_statements`의 호출당 블록 수가 시드 0일 때의 10배 이상인 질의가 2개 이상이다 | 그런 질의가 1개 이하 | 호출당 공유 블록, 호출/s, DB 시간/s | 인덱스 추가의 근거 없음 |
| Q10 | A5는 같은 질의의 호출당 블록을 A0의 1% 이하로 줄이고(`EXPLAIN (ANALYZE, BUFFERS)`, 이력 10만·50만·100만 행 3 크기 각 n=3 반복의 최댓값) 완료 일감당 WAL은 A0 대비 5% 넘게 늘리지 않는다 | 블록 감소 99% 미만 또는 WAL 5% 초과 증가 | 블록, WAL/일감 | 인덱스 출하 기각 |
| Q11 | A5의 `r_resume`·`p_claim` 호출 지연 중앙값은 A0의 10% 이하이고 다른 호출 p99는 A0보다 10% 넘게 나빠지지 않는다 | 위 기준 위반 | 호출 지연 | 인덱스 출하 기각 |

Q9~Q11이 모두 성립하면 인덱스 2개를 V8로 출하한다(데이터를 바꾸지 않는 인덱스 추가라 질의 의미는 그대로). 출하 후 같은 `EXPLAIN`이 새 인덱스를 쓰는지 PostgreSQL 통합 시험에서 확인하고, 인덱스를 지운 변이에서 시험이 실패해야 한다.

### 결정 규칙 (실행 전 고정)

레이아웃 변경(V8 마이그레이션)은 한 팔이 다음을 모두 만족할 때만 출하한다: (i) Q3 또는 Q5가 3 run 모두에서 성립하고 [min,max]가 A0와 겹치지 않으며, (ii) Q7이 성립하고, (iii) 절대값이 의미 있다: 해당 팔이 줄이는 WAL이 A0 총 WAL의 5% 이상이거나 청구 p99가 A0보다 낮다. 하나라도 어긋나면 현행을 유지하고 그 수치를 ADR에 적는다. 출하한다면 이득이 가장 큰 테이블 하나에만 적용하고 동작은 그대로 둔다.

## 영역 2: 깨우기 (E2)

### 대상 코드 경로

`LocationReservationWorker`·`CheckoutFinalizationWorker`·`LocationRestockWorker`(1초), `PaymentRecoveryWorker`(1초), `PayPalWebhookRetryWorker`(1초), `ReservationEventWorker`(0.2초), `PayoutDispatchWorker`(5초)는 `@Scheduled(fixedDelay)` 폴링이다.

### 방법

- 실제 앱 코드 경로를 쓰는 변형: 워커 한 종류(재고 예약 workflow)를 옵트인 `LISTEN/NOTIFY` 깨우기(연결 하나, 알림이 오면 즉시 poll, 안전망 폴링 유지)로 바꾼 구현을 만들고, 앱 통합 시험 수준의 PostgreSQL로 enqueue→claim 지연을 잰다. 비교군은 현행 폴링(1초). 별도로 순수 SQL 시제품으로 NOTIFY의 커밋 비용을 잰다.
- 측정: (a) enqueue 커밋 시각 → 청구 완료 시각 지연 분포(n=500건/조건, 조건당 run 3개), (b) 유휴 질의 부하: 일감 0일 때 `pg_stat_statements` calls/s, (c) 전용 리스너 연결이 Hikari 풀(10)과 서버 연결 수에 미치는 영향, (d) NOTIFY가 커밋 처리량에 미치는 영향(동시 커밋 클라이언트 1/8/32, NOTIFY 있음 대 없음, 5초×3 run).

### 가설과 반증 조건

| # | 가설 | 반증(발동) 조건 | 발동 시 |
|---|---|---|---|
| W1 | 폴링(1초) 지연은 중앙값 약 500 ms, p99 약 1000 ms다 | 중앙값이 350~650 ms 밖 | 모델(도착 균등, 주기 1초) 틀림. 원인 조사 |
| W2 | NOTIFY 깨우기의 중앙값은 50 ms 이하, p99는 200 ms 이하다 | 중앙값 > 50 ms 또는 p99 > 200 ms | 깨우기의 이점이 작아 기각 |
| W3 | 유휴 질의(calls/s)는 안전망 주기 30초일 때 폴링의 10% 이하다 | 10% 초과 | 이점 기각 |
| W4 | NOTIFY는 동시 커밋 32개에서 커밋 처리량을 10% 넘게 떨어뜨리지 않는다 | 10% 초과 하락 | 전역 알림 큐 락 비용이 이점보다 커서 기각 |
| W5 | 리스너 연결을 풀 밖(전용 연결)에 두면 Hikari 풀 점유가 0이다 | 풀에서 빼면 가용 연결이 줄어듦 | 연결 비용 문서화 |

결정 규칙: W2·W3·W4가 모두 성립하고 지연 단축이 사용자 영향 경로(체크아웃 재고 예약 workflow 완료)에서 의미 있을 때만 옵트인 기능으로 출하한다. 아니면 폴링 유지와 수치를 닫는다.

## 영역 3: 락 패턴 (D1·D2·D4)

### 3a. advisory 락 키

- 방법: 코드의 `pg_advisory_xact_lock` 호출 지점을 전수 나열하고 키 문자열의 형식을 정리한다. 서로 다른 자원 종류가 같은 키 문자열을 만들 수 있는지는 형식 언어로 판정한다. 64비트 해시 충돌은 생일 한계(`P ≈ n²/2^65`)를 실제 키 개수로 계산하고, `hashtextextended`의 균일성을 32비트로 잘라 실제로 검증한다(실제 형식의 키 100만 개, 잘린 32비트 충돌 개수의 기대값 약 116).
- 가설: 3a-1 서로 다른 자원 종류가 같은 키를 만드는 형식 쌍이 있다면 접두어 없는 UUID 문자열 키(주문, 결제 시도)가 후보다. 반증 조건: 형식 쌍의 언어가 교집합을 갖는다. 3a-2 동시에 보유한 서로 다른 키 개수 n=10^4일 때 충돌 확률은 10^-11 미만이다. 3a-3 잘린 32비트 충돌 개수가 기대값 ±4σ 안이다(밖이면 해시 균일성 가정 기각).
- 롤링 배포: 키를 바꾼다면 구·신 키를 **둘 다 잡는** 전환(재고 모듈이 이미 `legacyDelimited` + `encode`로 쓰는 방식)이 구·신 버전 혼재 때 상호 배제를 깨지 않는지를 혼재 시험으로 확인해야 한다. 이 시험은 키를 바꾸기로 결정한 경우에만 쓴다.

### 3b. 락 순서 순열 시험

- 대상 경로: 환불, 지급 디스패치, 정산 분개 기록, 재고 배치. 각 경로의 advisory 락과 행 락 획득 순서를 코드에서 뽑아 같은 판매자·주문·SKU를 두고 쌍(6)과 삼중(4)으로 무작위 시작 순서·장벽으로 겹친다. 시험의 검출력 검증(양성 대조)으로 일부러 반대 순서로 잠그는 변이 쌍을 넣는다.
- 가설: 3b-1 쌍·삼중 조합의 무작위 겹침 5,000회 이상에서 `40P01` 0건, `55P03` 0건, 불변식 위반 0건이다. 반증 조건: `40P01` 1건 이상 → 교착 존재, 최소 수정과 회귀 시험. 3b-2 양성 대조(반대 순서 변이)는 시험이 99% 이상 `40P01`을 잡는다(검출력). 반증 조건: 검출률 99% 미만 → 시험이 무력, 결과 폐기.

### 3c. SERIALIZABLE 대 advisory 락 (판매자 지급 가능액 예약)

- 읽고 계산한 뒤 쓰는 경로: 지급 가능액 = 지급 가능 − 대기 중 지급 − 환불 보류분 계산 후 예약 행 삽입. 현행(advisory 락, READ COMMITTED) 대 SERIALIZABLE + `40001` 재시도, 같은 판매자 동시 클라이언트 1/4/16/32.
- 가설: 3c-1 두 방식 모두 주입한 경쟁에서 초과 예약 0건이다(SERIALIZABLE은 재시도 후). 반증 조건: 초과 예약 1건 이상. 3c-2 SERIALIZABLE은 경쟁 16 클라이언트에서 처리량이 advisory 락의 90% 미만이거나 `40001` 비율이 5% 초과다(이면 현행 유지). 반증(SERIALIZABLE 채택 조건): 처리량 ≥ 90%이고 `40001` ≤ 5%이고 초과 예약 0건이며 코드 복잡도(재시도 래퍼)가 새 경로당 늘지 않음.

## 영역 4: 두 DB 원자성: 2PC 시제품 대 workflow/outbox (G3)

### 방법

- 컨테이너 두 개(order, location), `max_prepared_transactions` 켬. 재고 예약 한 단계(order 쪽 workflow 행 + location 쪽 재고 예약)를 (a) 현행 방식(order 의도 선커밋 → location 예약 커밋 → order 상태 커밋)과 (b) 2PC(양쪽 작업 → `PREPARE TRANSACTION` ×2 → 내구성 있는 결정 로그 → `COMMIT PREPARED` ×2)로 같은 업무 효과를 내게 구현하고 비교한다. 코디네이터는 결정 로그(파일 fsync)와 복구 sweep(`pg_prepared_xacts` 조회 후 결정 로그 대로 commit·rollback)을 가진 최소 구현.
- 칸: 성공 경로 지연·처리량, 코디네이터를 prepare와 commit 사이에서 죽였을 때(고아 prepared 트랜잭션: 같은 SKU 행에 대한 경쟁 쓰기 대기·실패 건수, `pg_locks`, vacuum 기준점(`xmin`) 고정과 "dead but not yet removable" 행 수, 복구 sweep까지 걸린 시간), location 참여자가 느리거나 죽었을 때(order 쪽 락 보유 시간), 운영 복잡도(추가 구성요소, 설정, 장애 런북 단계 수).
- 하이브리드: 2PC를 짧은 reserve 단계에만 쓰고 나머지는 현행 outbox·TCC를 유지하는 구성의 비용을 같은 표에 적는다.

### 가설과 반증 조건

| # | 가설 | 반증(발동) 조건 | 발동 시 |
|---|---|---|---|
| X1 | 2PC 성공 경로 p50은 현행 workflow(HTTP 제외, 같은 DB 왕복)의 1.5배 이상이다(커밋 fsync 두 번 + 결정 로그 fsync) | 1.5배 미만 | 지연 비용 반박. 다른 칸으로 결정 |
| X2 | 코디네이터를 prepare 뒤 커밋 전에 죽이면 같은 SKU의 경쟁 쓰기가 `lock_timeout`까지 100% 대기·실패한다 | 경쟁 쓰기가 대기 없이 성공 | 락 유지 서술 정정 |
| X3 | 고아 prepared 트랜잭션이 있는 동안 해당 테이블의 `VACUUM`은 그 트랜잭션 이후 죽은 행을 제거하지 못한다 | 모두 제거됨 | vacuum 정지 서술 정정 |
| X4 | 복구 sweep 주기 5초에서 고아가 해소되기까지 걸리는 시간은 주기+1초 이하다 | 초과 | sweep 구현 점검 |
| X5 | 참여자가 죽으면 2PC는 대기 시간만큼 order 쪽 락을 보유하고 현행은 0 s다 | 현행도 락 보유 | 현행 서술 정정 |

결정 규칙: 하이브리드는 (i) 성공 경로 p50 증가가 현행의 20% 이하이고, (ii) 코디네이터 사망 시 경쟁 쓰기 대기가 sweep 주기 이하로 끝나며, (iii) 부분 실패 상태 코드(`COMPENSATING`, tombstone 등)를 없애 줄인 코드 규모가 sweep·결정 로그 추가 코드보다 클 때만 시제품 경로를 만든다. 아니면 현행 유지, 뒤집을 증거(조건)를 문서에 쓴다.

## 출하 규칙

- 출하하는 변경마다 PostgreSQL 통합 시험과 변이 확인(고치기 전 실패, 후 통과), 기본값은 동작 보존, 되돌리기 절차, 기존 통합 시험 수정 없이 통과.
- 마이그레이션은 order DB `V8`, location DB `V5`만 쓴다. `V7`은 다른 PR 소유라 `V7`의 내용에 의존하지 않고 순서에 무관하게 적용되도록 쓴다.
- 마지막에 `fullcheck.sh`를 한 번(잠금 아래) 돌려 정확한 수치를 적는다.

## 범위 밖 결정

- CDC(Debezium 계열)와 외부 브로커 도입: 인프라 추가이고 이 실험의 질문(현행 폴링·큐 테이블의 비용)이 근거를 만들지 않는다. 재검토 조건: 큐 연령 p99가 목표를 넘거나 큐 테이블 WAL이 전체 WAL의 절반을 넘을 때.
