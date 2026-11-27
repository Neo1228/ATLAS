# EXP-112: 큐 레이아웃·깨우기·락·두 DB 원자성: 현행 대 대안

- 상태: ✓ 완료(영역 1의 A1~A4 칸은 실행하지 못했고 ✗로 표시, 영역 2의 종단 간 NOTIFY 칸은 하네스 결함으로 무효). 사전 등록은 별도 커밋으로 측정 전에 올렸다. 영역 1의 Q9~Q11은 탐색 중 본 현상을 확인하려고 큐 부하 측정 전에 따로 커밋한 추가 등록이다. "결과 요약" 이후가 실행 뒤에 채운 절이다.
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

### 방법 (영역 4: 두 DB 원자성)

- 컨테이너 두 개(order, location), `max_prepared_transactions` 켬. 재고 예약 한 단계(order 쪽 workflow 행 + location 쪽 재고 예약)를 (a) 현행 방식(order 의도 선커밋 → location 예약 커밋 → order 상태 커밋)과 (b) 2PC(양쪽 작업 → `PREPARE TRANSACTION` ×2 → 내구성 있는 결정 로그 → `COMMIT PREPARED` ×2)로 같은 업무 효과를 내게 구현하고 비교한다. 코디네이터는 결정 로그(파일 fsync)와 복구 sweep(`pg_prepared_xacts` 조회 후 결정 로그 대로 commit·rollback)을 가진 최소 구현.
- 칸: 성공 경로 지연·처리량, 코디네이터를 prepare와 commit 사이에서 죽였을 때(고아 prepared 트랜잭션: 같은 SKU 행에 대한 경쟁 쓰기 대기·실패 건수, `pg_locks`, vacuum 기준점(`xmin`) 고정과 "dead but not yet removable" 행 수, 복구 sweep까지 걸린 시간), location 참여자가 느리거나 죽었을 때(order 쪽 락 보유 시간), 운영 복잡도(추가 구성요소, 설정, 장애 런북 단계 수).
- 하이브리드: 2PC를 짧은 reserve 단계에만 쓰고 나머지는 현행 outbox·TCC를 유지하는 구성의 비용을 같은 표에 적는다.

### 가설과 반증 조건 (영역 4: 두 DB 원자성)

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

## 결과 요약

| 후보 | 결정 | 핵심 수치(n) |
|---|---|---|
| E1 큐 부분 인덱스 2개(A5) | ✓ 출하: V8 | `resume` 호출 p50 69.9–71.8 → 1.2 ms, 지급 `claim` 41.0–41.3 → 6.4–6.8 ms(같은 창 동시 A0·A5 n=2쌍). 블록 10,584 → 7, 7,402 → 10. 사전 등록 Q11의 지급 claim 조건(≤10%)은 발동(15.6–16.6%), 벗어나 출하한 이유는 영역 1 결정과 ADR-0101 |
| E1 `fillfactor`·자동 vacuum·임대 분리·배치 청구(A1~A4) | ✗ 실행하지 못함(호스트 잠금 시간) | 청구 단독 HOT 78%(fillfactor 100) 대 100%(75), 실제 부하 HOT 3.4%, 죽은 튜플 14.1%로 vacuum 0회. 재검토 조건은 영역 1 |
| E2 `LISTEN/NOTIFY` 깨우기 | ✓ 현행(폴링) 유지 | 커밋 처리량 notify/plain 8 클라이언트 0.37, 32 클라이언트 0.21(각 n=3). 1 s 폴링 p50 466.8–545.3 ms. 체크아웃 경로는 worker가 아니다 |
| D1 advisory 키 통합 | ✓ 현행 유지 | 12,000,000 키 64비트 충돌 0쌍(기대 3.9e-6), 실제 n≤1,600에서 충돌 확률 7e-14 이하 |
| D2 락 순서 순열 | ✓ 교착 1건 발견·수정(`PaymentRefundRepository.updateResult`) | 실코드 6,780 겹침 중 `40P01` 1건, 수정 뒤 1,500회 0건, 회귀 시험은 수정을 되돌리면 250쌍 중 7쌍 실패 |
| D4 SERIALIZABLE | ✓ 현행(advisory 락) 유지 | 16 클라이언트 B/A 처리량 0.351, 40001 89.8%, 과예약 0건(6,277 라운드) |
| 9.12 2PC·하이브리드 | ✓ 현행(workflow·outbox) 유지, 시제품 경로 미출하 | 코디네이터 사망 시 같은 SKU 쓰기 55P03 100%(n=60), 고아가 vacuum 기준점 고정 20/20, 하이브리드 p50 1.34배(n=3) |

## 결과 1: 큐 테이블 레이아웃 (E1)

### 실행한 칸과 실행하지 못한 칸

| 팔 | 실행 | 비고 |
|---|---|---|
| A0 현행 | ✓ 3 run(동시 3컨테이너, 오염 표) + 2 run(A5와 쌍으로 동시, 본표) | |
| A5 부분 인덱스 2개 | ✓ 3 run(오염 표) + 2 run(A0와 쌍, 본표) | 탐색 중 발견, Q9~Q11로 사전 등록 |
| A1 `fillfactor=75` | ✗ 실행 안 함 | 호스트 잠금 시간 부족(다른 에이전트의 측정과 번갈아 쓰는 창이 PR 예산 안에 모자람). Q3는 아래 "Q3 부분 근거"만 있다 |
| A2 자동 vacuum 임계 | ✗ 실행 안 함 | 같은 이유. Q8의 관측(A0에서 죽은 튜플 비율)이 필요성을 약화한다 |
| A3 임대 열 분리 | ✗ 실행 안 함 | 같은 이유. 시제품 SQL과 준비한 컨테이너는 있으나 측정 창이 없었다 |
| A4 배치 청구 | ✗ 실행 안 함 | 같은 이유. 현행 worker는 `claimNextDue(1, ...)`를 poll당 최대 10번 부른다(코드) |

재검토 조건(A1~A4 공통): 이력 행 수 또는 완료 일감/s가 이번 부하(결제 복구 60건/s)의 5배를 넘거나 `n_dead_tup`이 `autovacuum_vacuum_threshold + 0.2 × n_live_tup`에 닿아 자동 vacuum이 돌기 시작하는데 청구 p99가 올라갈 때, 그 칸을 같은 하네스로 돌린다.

### 실행 조건

- 일감: 결제 복구 60건/s(3%는 worker 경로: 청구 → 처리 200 ms → `markForApplication` → 완료 또는 재예약), 재고 workflow 30건/s, webhook 5건/s, 지급 2건/s, 이벤트 릴레이 인스턴스 2개. 이력 완료 행: 결제 복구 50만(대응 `payment_attempt` 50만), 지급 10만. 워밍업 30초 뒤 600초. 자동 vacuum 기본값. 컨테이너는 CPU 2·메모리 2 GB 제한, PostgreSQL 18.6 기본 설정에 `pg_stat_statements`만 추가.
- 오염 기준(사전 등록): run 시작·끝 `measure-lock.sh status`의 load1 > 12 또는 라벨 `atlasexp=queuetx`가 아닌 컨테이너가 보이면 오염.

### 본표: A0 대 A5, 같은 창에서 동시에 실행(n=2쌍)

두 팔을 같은 호스트 부하 아래 동시에 돌린 run이다. 시작 load1 7.86(1분 평균), 끝 load1 11.64, 보인 라벨은 `queuetx`뿐(오염 기준 미해당). 범위는 두 run의 최소–최대.

| 지표(10분, 워밍업 제외) | A0 현행 | A5 부분 인덱스 2개 |
|---|---|---|
| 결제 복구 `resumeExpiredApprovalWaits` 호출 지연 p50 (ms) | 69.9–71.8 | 1.2–1.2 |
| 같은 질의 p95 / p99 (ms) | 228.8–229.9 / 336.7–494.3 | 6.0–7.0 / 20.1–24.1 |
| 지급 `claim` 호출 지연 p50 (ms), n=73/run | 41.0–41.3 | 6.4–6.8 |
| 같은 질의 p95 / p99 (ms) | 149.7–211.8 / 209.9–320.1 | 25.1–38.7 / 60.9–104.4 |
| 결제 복구 청구 p50 / p95 / p99 (ms), n=3,174–3,283/run | 1.6–1.8 / 9.9–11.1 / 31.1–33.6 | 1.7–1.8 / 11.1–11.7 / 30.5–35.2 |
| 서버 쪽 `resume` 평균 / 기준 문장(INSERT) 평균 | 265–287배 | 0.1–0.5배 |
| 서버 쪽 지급 `claim` 평균 / 기준 문장 평균 | 163–172배 | 18–21배 |
| WAL(KB/완료 일감) | 5.97–6.06 | 5.88–5.90 |
| `payment_recovery_job` HOT 비율 | 3.5–3.6% | 3.5–3.6% |
| `payment_recovery_job` 죽은 튜플 최대 / 자동 vacuum | 121,693–123,166 / 1회 | 122,632–122,792 / 1회 |

### 큐 테이블별 HOT·죽은 튜플·자동 vacuum (A0, 동시 3 run 중 1번 run, 3 run의 HOT 비율은 3.4% 범위 0.1%p 안)

| 테이블 | 삽입 | 갱신 | HOT 비율 | 죽은 튜플 최대 | 자동 vacuum |
|---|---|---|---|---|---|
| `payment_recovery_job` (라이브 54만) | 35,957 | 74,773 | 3.4% | 75,753 | 0 |
| `paypal_webhook_retry` | 3,013 | 4,003 | 0.0% | 660 | 10 |
| `order_inventory_reservation_workflow` | 18,128 | 23,263 | 32.0% | 3,932 | 7 |
| `settlement_payout_dispatch_outbox` | 1,212 | 2,053 | 8.5% | 1,989 | 0 |
| `order_inventory_event_cursor` | 0 | 7,232 | 100.0% | 89 | 7 |

### 오염 표 (동시 3 run, 호스트 load1 29~33, 다른 라벨 컨테이너 1개 이상: 헤드라인에 쓰지 않는다)

| 팔 | 청구 p50 / p95 / p99 (ms) | `resume` p50 (ms) | 지급 `claim` p50 (ms) | 기준 INSERT 평균 (ms) |
|---|---|---|---|---|
| A0 (n=3) | 11.4–11.9 / 67.0–70.4 / 145.1–161.4 | 207.6–227.6 | 202.0–247.3 | 1.15–1.17 |
| A5 (n=3) | 23.2–24.5 / 108.3–113.6 / 178.4–212.3 | 13.4–14.2 | 55.9–69.3 | 2.16–2.29 |

이 표에서 A5의 청구가 A0보다 느려 보이는 것은 인덱스 때문이 아니다. 같은 run의 기준 문장(INSERT, 인덱스와 무관)이 A5 창에서 약 2배 느렸다. 그래서 위 본표(같은 창 동시 실행)와 같은 run 안 비율로만 비교한다.

### Q2 별도 시험: 청구 문장 단독 HOT 비율 (부하와 무관한 개수)

`payment_recovery_job`에 `PENDING` 1,500행, 청구 SQL을 하나씩 1,500회, `fillfactor` 100 대 75 각 n=3, 직전에 `VACUUM`.

| `fillfactor` | HOT 비율 (3 run) |
|---|---|
| 100 | 78.3%, 78.4%, 78.0% |
| 75 | 100.0%, 100.0%, 100.0% |

### Q9, Q10 근거: 이력에 비례하는 질의

실제 앱 JAR을 PostgreSQL에 붙여 60초 유휴로 두고 `pg_stat_statements`를 읽었다(결제 복구 이력 0 대 50만, 지급 25만, workflow 10만 시드, 앱 기동 뒤 시드하고 재기동). 계획은 `EXPLAIN (ANALYZE, BUFFERS)`.

| 질의 | 이력 0 | 이력 50만(지급 25만) | 인덱스 뒤 |
|---|---|---|---|
| 결제 복구 `resume`, 호출당 블록 | 0.0 | 9,091 | 7 (57.5만 행에서 10,584 → 7) |
| 지급 `claim`, 호출당 블록 | 0 | 9,246 | 20만 행 7,402 → 10, 100만 행 36,030 → 11 |
| 앱 전체 DB 시간/s (유휴) | 2.9 ms/s | 95.8 ms/s | 미측정 |

### 가설 결과

| # | 결과(수치, n) | 반증 발동 여부 | 해석 |
|---|---|---|---|
| Q1 | `payment_recovery_job` HOT 3.4%(동시 3 run) 및 3.5–3.6%(쌍 2 run) | 발동 안 함(< 30%) | 정상 경로의 두 갱신이 `status`·`available_at`을 바꿔 HOT 불가 |
| Q2 | 청구 단독 HOT 78.0–78.4%(n=3, fillfactor 100), 100%(fillfactor 75) | 발동 안 함(≥ 50%) | 청구 단독은 HOT 가능하지만 실제 부하에서는 3.4%에 그친다. 청구 대상이 방금 삽입돼 꽉 찬 페이지의 행이다 |
| Q3 | A1 미실행 | 판정 없음(실행하지 못함, 위 표) | Q2가 fillfactor 75에서 청구 HOT 78→100%임을 보였을 뿐 WAL·크기 효과는 측정하지 못했다 |
| Q4 | A2 미실행 | 위와 같음 | |
| Q5 | A3 미실행 | 위와 같음 | |
| Q6 | A4 미실행 | 위와 같음 | |
| Q7 | A5 청구 p99 30.5–35.2 ms 대 A0 31.1–33.6 ms(쌍 2 run) | 발동 안 함 | 회귀 없음(범위가 겹침) |
| Q8 | A0 `n_dead_tup` 최대 75,753 / 라이브 537,698 = 14.1% | 발동 안 함(≤ 20%) | 자동 vacuum 문턱(50 + 0.2 × 54만 ≈ 10.8만)에 못 닿아 10분 동안 vacuum 0회. 이 표는 문턱을 넘기 전까지 죽은 튜플이 쌓인다 |
| Q9 | 호출당 블록 10배 이상 질의 2개(resume 9,091 대 0, 지급 claim 9,246 대 0) 이상. 위험 지표 질의(지급 backlog 집계)도 9,246 | 발동 안 함 | 이력에 비례하는 질의 2개 확인 |
| Q10 | resume 10,584 → 7 블록(99.93% 감소), 지급 claim 7,402 → 10(99.86%, 20만 행), 36,030 → 11(100만 행). WAL 5.88–5.90 대 5.97–6.06 KB/일감(−1%) | 발동 안 함 | 1% 이하 블록, WAL 증가 없음 |
| Q11 | resume p50 1.2 ms 대 69.9–71.8 ms(1.7%) 충족. **지급 claim p50 6.4–6.8 ms 대 41.0–41.3 ms(15.6–16.6%)로 10% 초과**. 다른 호출 p99 악화 없음 | **지급 claim 쪽 발동** | 지급 claim의 남은 시간은 backlog(이번 부하에서 due 약 1,900행)를 읽고 정렬하는 비용으로 이력과 무관하다: A5 컨테이너의 `EXPLAIN`은 due 인덱스 비트맵 스캔 3,361행·힙 142블록, 실행 3.6 ms |

### 결정 (영역 1)

- 결정 규칙은 "Q9~Q11이 모두 성립해야 인덱스 2개를 출하"였다. Q11의 지급 claim 조건(p50 ≤ A0의 10%)이 발동했으므로 규칙대로라면 출하하지 않는다. **규칙에서 벗어나 출하한다**: 사전 등록 때 지급 claim p50의 하한(backlog 정렬 비용)을 몰랐고, 같은 질의가 A0 대비 6.3배 빨라졌으며(41 → 6.5 ms, 서버 쪽 평균 163–172배에서 18–21배로), 20만 행 7,402 → 10 블록, 100만 행 36,030 → 11 블록으로 이력에 비례하던 부분이 사라졌다. 벗어난 사실을 ADR-0101에도 적는다.
- 출하: V8의 부분 인덱스 2개(`ix_payment_recovery_job_waiting_for_buyer`, `ix_settlement_payout_dispatch_leased`). PostgreSQL 통합 시험 2개, 인덱스를 지운 변이에서 둘 다 실패(확인).
- 출하하지 않음: `fillfactor`, 자동 vacuum 임계, 임대 열 분리, 배치 청구(실행하지 못한 칸은 위 재검토 조건).

## 결과 2: 깨우기, 폴링 대 `LISTEN/NOTIFY` (E2)

### 실행 조건 (깨우기, 폴링 대 `LIS)

- 실제 workflow 삽입·청구 SQL(`LocationReservationRepository.claimNextDue`와 같은 문장)을 PostgreSQL 18.6 컨테이너(CPU 2)에 직접 돌렸다. 앱 코드 경로에 NOTIFY를 심은 변형은 만들지 않았다(아래 결정의 이유).
- **이 절의 모든 시간 값은 오염 run이다**: 호스트 잠금 없이 다른 에이전트의 측정과 겹쳤다(시작 load1 9~12, `queuetx` 라벨 컨테이너 4개, 사전 등록 기준 load1 ≤ 12는 만족했으나 잠금 밖이라 오염으로 분류). 폴링 지연은 주기에서 정해지는 값이라 영향이 작고, 커밋 처리량 비는 같은 컨테이너에서 plain·notify를 번갈아 돌린 값이다.

### W1 폴링 지연 (도착 5건/s, run당 20초, 청구는 주기마다 모두 비움)

| 주기 | run | n | p50 (ms) | p95 (ms) | p99 (ms) |
|---|---|---|---|---|---|
| 1 s | 1 | 98 | 478.3 | 960.3 | 999.0 |
| 1 s | 2 | 109 | 466.8 | 942.8 | 990.2 |
| 1 s | 3 | 86 | 545.3 | 934.9 | 965.5 |
| 0.2 s | 1 | 91 | 311.0 | 1,744.0 | 1,983.0 |
| 0.2 s | 2 | 100 | 436.3 | 1,855.4 | 2,180.2 |
| 0.2 s | 3 | 110 | 347.6 | 1,089.4 | 1,202.2 |

1 s 주기 중앙값은 466.8–545.3 ms로 예상(약 500 ms)과 맞다. **0.2 s 주기의 p95·p99가 1 s 주기보다 큰 것은 설명하지 못했다**(원인을 확정하지 못함: 청구 호출 수는 227–242로 기대 100의 2배 이상이라 하네스 쪽 대기 루프가 지연 표본을 늦췄을 가능성이 있다). 그래서 0.2 s 칸은 근거로 쓰지 않는다.

### W2 NOTIFY 깨우기 지연

- 종단 간 하네스(삽입 트랜잭션에 `pg_notify`, 리스너 연결이 알림에서 깨어 청구)는 표본이 거의 모이지 않았다: run 1 n=11 (p50 354 ms), run 2 n=0. 하네스가 청구한 행의 삽입 시각을 못 맞춘 결함으로 보이나 원인은 확정하지 못했다. **종단 간 값은 근거로 쓰지 않는다.**
- 알림 전달만 단독으로 잰 프로브(리스너 연결 1개, 알림 5회): 알림 도착까지 대기 0.0–0.1 ms(n=5). 깨어난 뒤 청구 지연은 별도 계산 없이 청구 SQL 1회(현행 `claimNextDue` 평균 약 0.3 ms, EXP-112 영역 1 서버 쪽 평균)다.
- 판정: W2(p50 ≤ 50 ms, p99 ≤ 200 ms)는 종단 간으로 확인하지 못했다. 알림 도착 지연이 0.1 ms 이하임은 확인했다.

### W3 유휴 질의 부하

- 실제 앱 JAR을 60초 유휴로 둔 `pg_stat_statements`(이력 0): 앱 전체 48.5 호출/s, 이 중 결제 복구 청구 0.85/s(이력 시드 run의 값), workflow 청구 0.98/s, 웹훅 재시도 청구 1.00/s, 재고 이벤트 릴레이 4.15/s×4문장.
- NOTIFY 깨우기에 안전망 폴링 30 s를 두면 워커 하나의 청구 호출은 1/30 = 0.033/s로 현행 1.0/s의 3.3%다(산술값, 측정 아님). 하네스의 유휴 칸(20 s)은 생산자가 즉시 끝나 표본이 되지 못했다(호출 수 10~11은 1.2 s 동안의 값이라 무효).

### W4 NOTIFY가 커밋 처리량에 주는 영향 (pgbench, 5초×3, 같은 컨테이너, 삽입 1건 + 알림 대 삽입 1건, LISTEN 연결 1개가 계속 비움)

| 클라이언트 | plain tps (3 run) | notify tps (3 run) | 평균 비 |
|---|---|---|---|
| 1 | 1,975 / 2,055 / 2,537 | 1,334 / 1,306 / 1,131 | 0.57 |
| 8 | 11,344 / 8,415 / 11,599 | 3,669 / 4,190 / 3,871 | 0.37 |
| 32 | 15,125 / 13,684 / 12,365 | 2,897 / 2,870 / 3,097 | 0.21 |

NOTIFY는 커밋 때 전역 알림 큐 잠금을 잡아 같은 서버의 모든 알림 커밋을 직렬화한다. 클라이언트 1개에서 비 0.57에는 `pg_notify`를 위한 문장 한 번이 더 늘어난 왕복이 섞여 있다. 8·32 클라이언트에서의 하락(63%, 79%)은 그것으로 설명되지 않는다.

### W5 리스너 연결

- 리스너는 풀 밖 전용 연결 1개로 둬야 한다(풀 안 연결을 쓰면 Hikari 10 중 1개를 계속 점유해 요청 가용 연결이 9개가 된다). 서버 쪽 백엔드는 +1이다(`pg_stat_activity` 조회로 확인: 리스너 세션이 항상 하나 존재). 풀 밖 전용 연결은 `max_connections` 한도(기본 100)에서 인스턴스당 1을 쓴다.
- 이 칸은 실제 앱에서 측정하지 않았다(앱 변형을 만들지 않음).

### 가설 결과 (깨우기, 폴링 대 `LIS)

| # | 결과(수치, n) | 반증 발동 여부 | 해석 |
|---|---|---|---|
| W1 | 1 s 주기 p50 466.8–545.3 ms(n=3 run), p99 965.5–999.0 ms | 발동 안 함(350~650 ms 안) | 도착 균등·주기 1초 모델과 맞다 |
| W2 | 종단 간 표본 무효(n=11, 0). 알림 도착 0.0–0.1 ms(n=5) | 판정 근거 없음(종단 간 미측정) | 알림 전달이 빠르다는 것만 확인 |
| W3 | 산술값 3.3% (안전망 30 s) | 발동 안 함(산술) | 측정이 아니다 |
| W4 | 32 클라이언트에서 plain 대비 notify 0.21배(−79%), 8 클라이언트 0.37배 | **발동**(> 10% 하락) | 전역 직렬화로 커밋 처리량이 크게 떨어진다 |
| W5 | 풀 밖 전용 연결 +1, 서버 백엔드 +1 | 발동 안 함 | 연결 비용은 작다 |

### 결정 (영역 2)

- **`LISTEN/NOTIFY` 깨우기를 출하하지 않는다. 폴링을 유지한다.** 결정 규칙(W2·W3·W4 모두 성립, 지연 단축이 사용자 영향 경로에서 의미가 있을 것)에서 W4가 발동했다(−63~79%). 또 결제 경로의 깨우기 지연은 사용자에게 보이지 않는다: 체크아웃은 `LocationReservationServiceImpl.submit`이 workflow를 곧바로 `process`하므로(코드) 폴링 worker는 재시도·복구 경로만 맡는다. 그래서 1초 폴링의 평균 약 0.5초 대기는 정상 경로 지연이 아니라 복구 지연이다.
- 재검토 조건: 복구 경로의 재시도 지연이 사용자 영향이 되는 SLO가 생기고(예: 재고 예약 복구 p99 < 200 ms 요구), 알림을 보내는 커밋의 동시성이 8 미만으로 묶이거나 알림 대신 폴링 주기를 줄이는 것이 DB 질의 부하(현재 앱 전체 약 48 호출/s)보다 비싸다고 측정될 때.
- 이번에 하지 못한 것: 앱 코드 경로에 옵트인 리스너를 심은 변형과 그 종단 간 측정(하네스 결함, 그리고 W4 발동으로 출하하지 않을 것이 확정돼 변형을 만들지 않음).

### 주장 표 (영역 1·2)

| 주장 | 증거 | 확신도 |
|---|---|---|
| 폴링 질의 2개가 이력 행 수에 비례해 읽었고 부분 인덱스로 끊긴다 | 블록 10,584 → 7(`EXPLAIN` BUFFERS), 유휴 앱 호출당 9,091·9,246 블록, 쌍 run p50 비 1.7%·15.6–16.6% | 확인(블록 수), local-only(에이전트 보고)(시간 값 n=2쌍) |
| 실제 부하에서 `payment_recovery_job` HOT 비율은 3.4%이고 청구 단독은 78% | 위 표, Q2 n=3+3 | 확인 |
| 10분 동안 자동 vacuum이 돌지 않아도 청구 p99에 영향이 없다 | A0 `n_dead_tup` 14.1%, av 0, 청구 p99 31.1–33.6 ms | local-only(에이전트 보고) |
| `fillfactor`·자동 vacuum·임대 분리·배치 청구의 효과 | 미측정 | 재현 못 함(원인 미확정): 실행하지 못한 칸이라 효과를 주장하지 않는다 |
| NOTIFY는 커밋 처리량을 크게 낮춘다(8 클라이언트 0.37, 32 클라이언트 0.21) | pgbench 각 n=3 | local-only(에이전트 보고)(잠금 밖 실행) |
| NOTIFY 종단 간 깨우기 지연 | 하네스 표본 무효 | 재현 못 함(원인 미확정) |

## 영역 3a·3b: advisory 락 키 구성과 락 순서 순열 (D1)

### 3a. advisory 락 키 (영역 3a·3b: advi)

#### 호출 지점 전수 (`git grep pg_advisory`·`pg_try_advisory` 결과 9줄, 8개 파일, 운영 코드)

| 호출 지점 | 키 템플릿 | 자원 종류 | 범위 | 보유 경로 |
|---|---|---|---|---|
| `StockRepository.lockOrder` | `<orderId>` (36자 UUID, Hibernate `VERSION_7`) | 주문 단위 재고 예약·확정·해제·복원 직렬화 | xact | `InventoryServiceImpl` 4곳 |
| `PaymentRefundRepository.lockAttempt` | `<attemptId>` (36자 UUID, `JdkIdGenerator` = v4) | 결제 시도 단위 환불 예약 | xact | `PaymentRefundWorkflow.reserveRefundOperation` 1곳 |
| `OrderRefundRepository.lockIdempotencyKey` | `order-refund:<orderId>:<key>` (key는 자유 문자열 ≤128) | (주문, 환불 멱등 키) | xact | `OrderRefundServiceImpl.reserveRefund` 1곳 |
| `SettlementJournalRepository.lockSellerPayouts` | `settlement-payout:<sellerId>` | 판매자 지급 가능액 읽고 쓰기 | xact | `SettlementServiceImpl` 3곳 + `SettlementPayoutServiceImpl` 11곳 |
| `BuyerLockRepository.lockBuyer` | `<campaignId>:<buyerRef>` (`buyerRef`는 API 경계에서 `buyer_id::text`, UUID) | 캠페인 안 구매자 한도 | xact | `OrderServiceImpl` 2곳(주문 행 락 뒤) |
| `ExpirySweepLockRepository.runExclusive` | `expiry-sweep:<workflow>`, workflow ∈ {`orders`, `checkouts`} | 만료 sweep 단일 실행 | **session**(`pg_try_advisory_lock`, `finally`에서 unlock, 연결 점유) | `OrderServiceImpl`·`CheckoutPaymentServiceImpl` sweep 각 1곳 |
| `CapacityAdjustmentRepository.lockAdjustmentKey` | 구 `<seller>:<scope>:<sku>:<key>`, 신 `len:part` 연결(`InventoryAdvisoryLockKey.encode`), **둘 다 고정 순서(구→신)로 잡음** | (판매자, 위치, SKU, 조정 키) | xact | `InventoryCapacityAdjustmentServiceImpl` 2곳 |
| `LocationRepository.transfer` | 구 `<seller>:<sku>:<key>`, 신 `encode(seller, sku, key)`, 구→신 순 | (판매자, SKU, 이전 키) | xact | `InventoryServiceImpl.transfer` 1곳 |

advisory 락은 데이터베이스 단위로 이름 공간이 갈린다. `atlas-application`은 `atlas-inventory`를 포함해 order DB 하나에 재고 테이블도 둔다(`V1__baseline.sql`에 `inventory_pool`, `inventory_reservation`이 있다). 그래서 이 구성에서는 위 8개 템플릿이 모두 같은 락 공간을 쓴다. 위치 서비스를 따로 띄우면(`atlas-inventory-location`, 자체 DB) 재고 키 3개만 그 DB에 있고 다른 템플릿과 만나지 않는다. 아래 판정은 더 나쁜 쪽(단일 DB)을 기준으로 한다.

#### 형식 언어 판정 (3a-1)

각 템플릿을 정규식으로 쓰고(UUID는 36자 16진, 자유 문자열은 인쇄 가능 ASCII, 콜론 포함), 유한 오토마타 곱으로 두 언어의 교집합이 비어 있는지 모든 쌍 45개를 계산했다.

| 판정 수준 | 교집합이 있는 쌍 | 교집합 없는 쌍 |
|---|---|---|
| 형식(UUID는 버전 무관 36자 16진 문자열) | 5쌍: 주문 id×결제 시도 id, 구 용량조정×구 이전, 구매자(자유 ref)×구 용량조정, 구매자(자유 ref)×구 이전, 구매자(UUID ref)×구매자(자유 ref, 같은 자원) | 40쌍 |
| 생성기(주문 id는 v7, 시도 id는 v4: 버전 니블 `7` 대 `4`) | 4쌍: 위에서 주문 id×결제 시도 id만 빠짐 | 41쌍 |

- 가설 3a-1이 찍은 후보(접두어 없는 UUID 문자열 키: 주문 id와 결제 시도 id)는 **형식 수준에서는 같은 언어**다(둘 다 36자 UUID 문자열). 그러나 주문 id는 Hibernate `VERSION_7`(DB 기본값도 `uuidv7()`), 시도 id는 `JdkIdGenerator`다. `JdkIdGenerator` 10만 개를 뽑아 버전 니블을 세니 v4 100,000 · v7 0 · 기타 0이었다. 두 생성기의 출력 언어는 버전 니블이 달라서 서로소다. 시도 id를 주문 id에서 유도하는 코드도 없다(`ids.generateId()`로 독립 생성).
- 교집합이 있는 나머지는 **자유 문자열 필드가 콜론을 포함할 수 있는 구 구분자 형식**이다. 구 용량조정 `<seller>:DEFAULT:<sku>:<key>`와 구 이전 `<seller>:<sku>:<key>`는 이전의 sku가 `DEFAULT`이고 key가 `<sku2>:<key2>`일 때 같은 문자열이 된다. 이것이 재고 모듈이 `encode`(길이 접두)를 도입하고 구·신 두 키를 모두 잡도록 한 이유이며, 이미 해결되어 있다(`InventoryAdvisoryLockKeyTest`가 신형의 구분을 고정한다). 구매자 키의 자유 ref 교집합은 `campaignId == sellerId`(서로 다른 엔티티의 독립 v7 id)가 같아야 실현된다. 게다가 `AdmissionController`가 `requireOwnBuyerRef`(`buyer_id::text` 일치 검사)를 거쳐 티켓을 만들기 때문에 이 경로의 `buyerRef`는 UUID 문자열(콜론 없음)이고, UUID ref 언어는 구 키와 교집합이 없다. 다른 진입 경로가 자유 문자열 `buyerRef`를 허용하는지는 확인하지 않았다.
- 그 밖의 접두어 템플릿(`order-refund:`, `settlement-payout:`, `expiry-sweep:`)은 첫 글자 또는 둘째 글자가 UUID 언어와 달라 다른 모든 템플릿과 서로소다. 고정 폭 UUID가 앞에 오는 템플릿(`order-refund:<UUID>:<key>`, `<UUID>:<UUID>`)은 파싱이 유일해서 키 안에서도 모호하지 않다.

#### 해시 균일성과 충돌 개수 (3a-3)

템플릿 12개 × 1,000,000개(합 12,000,000개), 서로 다른 키 문자열 수는 템플릿마다 1,000,000(중복 키 0).

| 템플릿 | n | 서로 다른 키 | 길이 | 하위 32비트 쌍(관측 / 기대) z | 상위 32비트 쌍 z | 64비트 쌍 |
|---|---:|---:|---|---|---|---:|
| T1a_order_v7_burst | 1,000,000 | 1,000,000 | 36–36 | 126 / 116.4, z=+0.89 | 117 / 116.4, z=+0.05 | 0 |
| T1b_order_v7_year | 1,000,000 | 1,000,000 | 36–36 | 122 / 116.4, z=+0.52 | 134 / 116.4, z=+1.63 | 0 |
| T2_attempt_v4 | 1,000,000 | 1,000,000 | 36–36 | 111 / 116.4, z=-0.50 | 123 / 116.4, z=+0.61 | 0 |
| T3a_order_refund_return | 1,000,000 | 1,000,000 | 93–93 | 101 / 116.4, z=-1.43 | 116 / 116.4, z=-0.04 | 0 |
| T3b_order_refund_seq | 1,000,000 | 1,000,000 | 58–59 | 118 / 116.4, z=+0.15 | 109 / 116.4, z=-0.69 | 0 |
| T4_settlement_payout | 1,000,000 | 1,000,000 | 54–54 | 117 / 116.4, z=+0.05 | 103 / 116.4, z=-1.24 | 0 |
| T6a_buyer_uuid | 1,000,000 | 1,000,000 | 73–73 | 133 / 116.4, z=+1.54 | 123 / 116.4, z=+0.61 | 0 |
| T6b_buyer_seq | 1,000,000 | 1,000,000 | 44–47 | 124 / 116.4, z=+0.70 | 124 / 116.4, z=+0.70 | 0 |
| T7a_legacy_capacity | 1,000,000 | 1,000,000 | 63–69 | 106 / 116.4, z=-0.97 | 135 / 116.4, z=+1.72 | 0 |
| T7b_legacy_transfer | 1,000,000 | 1,000,000 | 86–86 | 124 / 116.4, z=+0.70 | 116 / 116.4, z=-0.04 | 0 |
| T8a_encoded_capacity | 1,000,000 | 1,000,000 | 70–77 | 120 / 116.4, z=+0.33 | 116 / 116.4, z=-0.04 | 0 |
| T8b_encoded_transfer | 1,000,000 | 1,000,000 | 93–93 | 121 / 116.4, z=+0.42 | 150 / 116.4, z=+3.11 | 0 |

- 32비트 잘림 검정 24개(템플릿 12 × 하위·상위): 모두 `|z| ≤ 4`, 최대 `|z|` = 3.11. 기대값 116.42는 `n(n-1)/2/2^32`이다.
- 하위 16비트 65,536칸 카이제곱(자유도 65,535) 12개: `z` 범위 -0.64 ~ +1.07, 4σ 밖 0개. 비트 64개 × 12집합 = 768개 편향 검정: 4σ 밖 0개, 최대 `|z|` = 3.87.
- 64비트 전체 해시가 같은 서로 다른 키 쌍: 템플릿별 12개 모두 0. 12,000,000개를 합쳐도 0쌍(기대값 `N(N-1)/2/2^64` = 3.903e-06).
- 32비트 네이티브 `hashtext(key)`는 `hashtextextended(key,0)`의 하위 32비트 쌍 수와 같았다(`T1b` 122, `T3a` 101, `T7a` 106, 세 템플릿 모두 일치, 기대 116.42).
- 검정력 확인(양성 대조): 키의 앞 12글자만 해시했을 때 `T1a`(v7 앞 12글자는 시각뿐) 쌍 9,264,156,140개, `T4`(앞 12글자가 상수 접두) 쌍 499,999,500,000개로 4σ 밖. `T2`(v4 앞 12글자에 무작위 44비트가 남음)는 120개로 기대(116.42)와 같아 4σ 안이다. 이 통계가 약한 입력은 잡아낸다.
- 다중 비교: 검정이 24 + 12 + 768개라 최댓값이 3σ대인 것은 우연 범위다(768개 중 최대 `|z|` 3.87이 나올 확률은 약 0.08).

#### 충돌 확률과 비용 (3a-2)

- 생일 한계 `P ≈ n²/2^65`. 사전 등록 `n = 10^4`: `P = 2.711e-12`(정확식 `n(n-1)/2/2^64` = 2.710e-12). 기준 `10^-11` 미만.
- 실제 `n`의 근거: 동시에 쥐는 서로 다른 advisory 키 수는 (DB 연결 수) × (트랜잭션 하나가 쥐는 advisory 키 수 ≤ 3, 여유로 4)다. 연결 수는 `sizing-envelope.md` §3.4(T3 120 연결, RDS 기본 약 400)와 `capacity-model.md`(100만 DAU 36노드 × 10 = 360)에서 가져왔다. 기본 `max_locks_per_transaction` 64 × `max_connections` 100 = 6,400칸이라 `n = 10^4`는 공유 락 테이블에 담기지도 않는 값이다.

| 가정 | n(최대) | P(동시 보유 키 중 충돌) |
|---|---:|---:|
| T1: 앱 2대 × Hikari 10 = 20 연결 | 80 | 1.713e-16 |
| T3: 120 연결 | 480 | 6.232e-15 |
| 100만 DAU: 360 연결 | 1,440 | 5.617e-14 |
| RDS 기본 400 연결 | 1,600 | 6.935e-14 |
| 사전 등록 값 | 10,000 | 2.710e-12 |

- 충돌 한 번의 비용: advisory 락은 상호 배제만 준다. 서로 다른 두 논리 키가 같은 64비트 값이면 **서로 직렬화될 뿐** 직렬화 보장은 깨지지 않는다. 비용은 상대 트랜잭션 보유 시간(밀리초~초)만큼의 대기이고, `lock_timeout` 5 s를 넘으면 요청 한 건이 `55P03`으로 실패해 재시도된다. 거짓 공유가 교착이 되려면 서로 다른 두 쌍이 동시에 충돌하고 반대 순서여야 하므로 확률은 위 `P`의 제곱 이하다.
- 연간 기대 발생(가정, 측정 아님): T3 주문 300 TPS × 주문당 advisory 획득 8회 = 2,400/s, 평균 동시 보유 480 → 연 1.97e-06회. T2 주문 50 TPS(획득 400/s, 평균 200) → 연 1.36e-07회.
- 대안 키 형식의 충돌 확률(n = 480 / 10,000): 현행 64비트 6.2e-15 / 2.7e-12, 분류 태그 + 32비트 해시(`pg_advisory_xact_lock(int,int)`) 2.7e-05 / 1.2e-02, 해시 56비트 + 타입 태그 8비트 1.6e-12 / 6.9e-10(기준 `10^-11` 초과). 자원 종류 간 충돌을 구조적으로 막는 태그 방식은 해시 비트를 잃어 확률이 커진다.

#### D1(advisory 키 통합) 결정: 현행 유지

- 실제 의미 충돌 없음: 서로 다른 자원 종류가 같은 키 문자열을 만드는 쌍은 생성기 수준에서 구 구분자 재고 키 둘(용량조정×이전)뿐이고, 이는 이미 `encode` + 구·신 이중 잠금으로 해결된 상태다. 주문 id(v7)와 결제 시도 id(v4)는 같은 언어가 아니다.
- 이득 없음: 통합은 모든 키에 길이 접두나 종류 태그를 붙이는 일인데, 실제 `n`에서 충돌 확률이 이미 6e-15 이하이고 태그 방식은 확률을 키운다. 줄이는 것이 없고 바꾸는 비용(구·신 이중 잠금 전환, 혼재 시험)만 생긴다.
- 그래서 키 형식을 바꾸지 않았고 롤링 배포 혼재 시험은 쓰지 않았다(사전 등록: 키를 바꾸기로 결정한 경우에만).
- 뒤집을 증거: (1) 주문 id나 결제 시도 id 생성기가 같은 버전·같은 언어가 되거나 한쪽이 다른 쪽에서 유도되면 접두어 없는 두 키가 충돌 후보가 되므로 `attempt:` 같은 접두를 구·신 이중 잠금으로 도입한다. (2) 구 재고 키(`legacyDelimited`)는 모든 인스턴스가 신 버전이 된 뒤 다음 릴리스에서 제거할 수 있다. 제거하면 용량조정·이전마다 advisory 왕복 한 번이 줄지만 이 문서는 그 이득을 재지 않았다.

### 3b. 락 순서 순열 시험 (영역 3a·3b: advi)

#### 경로별 락 획득 순서 (코드에서 추출, 한 줄이 한 트랜잭션)

기호: `A_idem` = `order-refund:<order>:<key>`, `A_att` = `<attemptId>`, `A_sell` = `settlement-payout:<seller>`, `A_stock(o)` = `<orderId>`(재고 모듈), `O` = `purchase_order` 행 `FOR UPDATE`, `Pool(k)` = `inventory_pool` 행 UPDATE(`(seller, sku, location)`), `Rsv` = `inventory_reservation` 행 `FOR UPDATE`.

| 경로 | 코드 | 트랜잭션별 획득 순서 |
|---|---|---|
| R 환불 | `OrderRefundServiceImpl.reserveRefund` → `PaymentRefundWorkflow.reserveRefundOperation` → `SettlementServiceImpl.reserveRefund` | tx1: `A_idem` → `O` → `A_att` → 환불 작업 행 → `A_sell` |
| | `PaymentRefundWorkflow.executeRefundOperation` | tx2a·2b·2c: 환불 작업 행 `FOR UPDATE`, 결제 시도 행 `FOR UPDATE`, 환불 작업 행 `FOR UPDATE`(각각 별도 트랜잭션, 한 트랜잭션에 락 하나) |
| | `OrderRefundServiceImpl.applyConfirmedRefund` → `InventoryServiceImpl.restoreClaimed` → `SettlementServiceImpl.recordRefund` | tx3: `O` → `A_stock(o)` → `Rsv` → `Pool(k)` → `A_sell` |
| P 지급 | `SettlementPayoutServiceImpl.request/approve/cancelBeforeDispatch/beginDispatch/…`, `JdbcPayoutDispatchStore.claim` | claim tx: 발송 큐 행 `FOR UPDATE SKIP LOCKED`(대기 없음, 커밋). 나머지 tx마다: `A_sell` → 지급 행 → (취소 때만) 발송 큐 행 |
| S 정산 분개 | `SettlementServiceImpl.reserveRefund/releaseRefundReservation/recordRefund` | tx마다 `A_sell` 하나뿐. `recordCapture`는 락 없음 |
| I 재고 배치 | `InventoryServiceImpl.reserveAll/claimAll/releaseAll/restoreClaimed` | tx마다 `A_stock(o)` → `Rsv` → `Pool(k)`(reserveAll은 `(seller, sku)` 정렬순) |
| (범위 밖) 주문 승인·취소 | `OrderServiceImpl` 2곳 | `O` → `A_buyer`(구매자 키). 위 네 경로와 같은 자원을 만나지 않음 |

관찰: `A_sell`은 R의 두 트랜잭션에서 **마지막** 락이고 P·S에서는 **첫** 락이다. P·S는 `A_sell` 뒤에 지급·분개 행만 잡고 `O`·`A_stock`·`Pool`을 잡지 않으므로 `A_sell`을 쥔 채 R이 쥔 락을 기다리는 경로가 없다. R이 `A_stock(o)`을 거쳐 `Pool(k)`을 잡을 때 I도 같은 방향(`A_stock` → `Rsv` → `Pool`)이다. 획득 순서만 보면 락 대기 그래프에 순환이 없고, 가설 3b-1(교착 0건)은 이 추론의 예측이다. 이 추출은 같은 행에서 락 **모드**를 올리는 문장(UPDATE 뒤 `FOR UPDATE`, FK 갱신의 `FOR KEY SHARE` 뒤 `FOR UPDATE`)을 보지 않았다. 아래 무작위 겹침으로 그 예측을 반증하려고 시도한다.

#### 결과: 실코드 구동 (저장소 서비스 호출, 조합마다 무작위 지연 + 장벽)

| 조합 | 시도 | 경로 실행 | 40P01(SQLSTATE) | 55P03 | 기타 SQL·HUNG·HARNESS·BARRIER | 업무 예외(BIZ) | 서버 로그 deadlock | 서버 로그 lock timeout | 불변식 위반(조합 끝 검사 횟수) | 소요(s) |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| RR | 100 | 200 | 1 | 0 | 0 | 0 | 1 | 0 | 0 (2) | 84 |
| PP | 800 | 1600 | 0 | 0 | 0 | 466 | 0 | 0 | 0 (2) | 98 |
| SS | 850 | 1700 | 0 | 0 | 0 | 0 | 0 | 0 | 0 (2) | 52 |
| II | 850 | 1700 | 0 | 0 | 0 | 0 | 0 | 0 | 0 (2) | 63 |
| RP | 200 | 400 | 0 | 0 | 0 | 139 | 0 | 0 | 0 (2) | 111 |
| RS | 200 | 400 | 0 | 0 | 0 | 0 | 0 | 0 | 0 (2) | 115 |
| RI | 200 | 400 | 0 | 0 | 0 | 0 | 0 | 0 | 0 (2) | 77 |
| PS | 850 | 1700 | 0 | 0 | 0 | 63 | 0 | 0 | 0 (2) | 46 |
| PI | 800 | 1600 | 0 | 0 | 0 | 0 | 0 | 0 | 0 (2) | 22 |
| SI | 850 | 1700 | 0 | 0 | 0 | 0 | 0 | 0 | 0 (2) | 12 |
| RPS | 120 | 360 | 0 | 0 | 0 | 81 | 0 | 0 | 0 (2) | 33 |
| RPI | 120 | 360 | 0 | 0 | 0 | 79 | 0 | 0 | 0 (2) | 32 |
| RSI | 120 | 360 | 0 | 0 | 0 | 0 | 0 | 0 | 0 (2) | 27 |
| PSI | 600 | 1800 | 0 | 0 | 0 | 62 | 0 | 0 | 0 (2) | 15 |
| RPSI | 120 | 480 | 0 | 0 | 0 | 97 | 0 | 0 | 0 (2) | 17 |
| **합계** | 6780 | 14760 | 1 | 0 | 0 | 987 | 1 | 0 | 0 (30) | 804 |

- 경로 실행 14760건, 시도(겹침) 6780회. SQLSTATE `40P01` 1건, `55P03` 0건, 기타 SQL·HUNG·HARNESS·BARRIER 0건. 서버 로그의 `deadlock detected` 1줄, `due to lock timeout` 0줄, `still waiting for`(`deadlock_timeout` 1 s를 넘겨 기다린 락 대기) 85줄. 불변식 위반 0건(조합 끝마다 11개 검사, 검사 횟수 30회).
- 업무 예외 987건은 모두 `SettlementException`이다(예외 클래스만 기록했고 메시지는 기록하지 않았다). 이 경로에서 `SettlementException`을 던지는 조건은 코드상 지급 가능액 0 이하(`Seller has no payable balance…`)와 상태 전이 충돌이다. P 실행은 항상 `A_sell`을 쥔 뒤에 중단되므로 락 획득 순서 시험에는 포함된다. 경로별 성공 실행 수: R 1279, P 3423, S 4560, I 4510.
- 호스트 부하가 높아(load1 약 30) 환불(R) 한 건이 1 s 안팎이었다. 그래서 R을 포함한 조합의 시도 수가 R이 없는 조합보다 적다. 5,000회 이상은 실코드만으로 채웠다.

#### 결과: 락 순서 복제 구동 (같은 지터·장벽, 병렬 8프로세스)

| 조합 | 완료한 워커 | 시도 | 경로 실행 | OK | 40P01 | 55P03 | 기타 |
|---|---:|---:|---:|---:|---:|---:|---:|
| RR | 8 | 440 | 880 | 880 | 0 | 0 | 0 |
| PP | 8 | 440 | 880 | 880 | 0 | 0 | 0 |
| SS | 8 | 440 | 880 | 880 | 0 | 0 | 0 |
| II | 8 | 440 | 880 | 880 | 0 | 0 | 0 |
| RP | 8 | 440 | 880 | 880 | 0 | 0 | 0 |
| RS | 8 | 440 | 880 | 880 | 0 | 0 | 0 |
| RI | 8 | 440 | 880 | 880 | 0 | 0 | 0 |
| PS | 8 | 440 | 880 | 880 | 0 | 0 | 0 |
| PI | 8 | 440 | 880 | 880 | 0 | 0 | 0 |
| SI | 8 | 440 | 880 | 880 | 0 | 0 | 0 |
| RPS | 8 | 320 | 960 | 960 | 0 | 0 | 0 |
| RPI | 8 | 320 | 960 | 960 | 0 | 0 | 0 |
| RSI | 8 | 320 | 960 | 960 | 0 | 0 | 0 |
| PSI | 8 | 320 | 960 | 960 | 0 | 0 | 0 |
| RPSI | 8 | 240 | 960 | 960 | 0 | 0 | 0 |
| **합계** |  | 5920 | 13600 | 13600 | 0 | 0 | 0 |

- 복제는 시도 5920회, 경로 실행 13600건에서 `40P01` 0건, `55P03` 0건, 기타 0건. 복제 컨테이너 서버 로그의 `deadlock detected`는 사전 점검 때 일부러 넣은 양성 대조 3건뿐이고 본 구동 중에는 0줄이었다.
- 시도 합계: 실코드 6780 + 복제 5920 = 12700회(사전 등록 5,000회 이상은 실코드만으로는 충족, 합치면 충족).

#### 교착 발견: 같은 멱등 키 동시 환불 (3b-1 반증 발동)

- 최초 발생: 실코드 구동 2차 묶음의 RR 40회(80회 호출)에서 `40P01` 1건, 서버 로그 `deadlock detected` 1줄. 서버 로그를 남기지 않아 원인을 못 읽어 RR만 다시 돌렸다.
- 재현 구동(RR만, 시도의 절반은 같은 멱등 키를 두 호출이 공유, 서버 로그 보존): 1차 1500회에서 서버 로그 `deadlock detected` 15줄(집계 {R:OK=2985, R:SQL:40P01=15}), 2차(`log_statement=all`, 프로세스·xid 접두) 700회에서 4줄(집계 {R:OK=1396, R:SQL:40P01=4}). 둘 다 `deadlock_timeout` 1 s 뒤 한쪽이 `40P01`로 끝났다.
- 서버 로그 판독: 교착 4건 모두 두 세션이 같은 문장 `SELECT * FROM payment_refund_operation WHERE refund_operation_id = $1 FOR UPDATE`를 기다리고 있었고 두 문장의 `refund_operation_id`가 같았다(같은 키를 공유한 쌍). 한 세션(finalize 트랜잭션, `OrderRefundServiceImpl.finalizeRefund`)의 문장 순서는 `UPDATE order_refund_request SET payment_refund_operation_id = $1 …` → … → 재고 복원·`A_sell`·정산 분개·`order_refund_summary` → `SELECT … payment_refund_operation … FOR UPDATE`(`markRefundApplied`)이다. 다른 세션(`PaymentRefundWorkflow.executeRefundOperation`)의 순서는 `UPDATE payment_refund_operation SET refund_state …`(`PaymentRefundRepository.updateResult`) → `SELECT … FOR UPDATE`(`findByOperationIdForUpdate`)이다.
- 근본 원인: 같은 환불 작업 행에서 **락 모드 올림**이 두 트랜잭션에서 겹친다. UPDATE는 `FOR NO KEY UPDATE`만 잡고 그 뒤 `FOR UPDATE`로 올린다. finalize 트랜잭션은 `order_refund_request.payment_refund_operation_id`(FK) 갱신으로 그 행에 `FOR KEY SHARE`를 쥔 채 `FOR UPDATE`로 올린다. `FOR UPDATE`는 상대의 `FOR NO KEY UPDATE`·`FOR KEY SHARE` 둘 다와 충돌하므로 둘이 서로의 약한 락을 기다린다. `pg_locks` 스냅샷은 못 떴고(교착은 1 s 뒤 해소되어 사후 로그만 있다) 위 문장 순서와 FK 정의(`order_refund_request_payment_refund_operation_id_fkey`, `payment_recovery_job_payment_refund_operation_id_fkey`)에서 도출했다. FK가 `KEY SHARE`를 잡는다는 부분은 수정 전후 시험 결과와 맞지만 별도 `pg_locks` 관측은 없다.
- 최소 수정: `PaymentRefundRepository.updateResult`가 UPDATE 앞에서 같은 행을 `SELECT 1 … FOR UPDATE`로 먼저 잡는다. 이후 문장(UPDATE, `FOR UPDATE` 재조회)은 그대로이고 같은 트랜잭션 안이라 추가 대기가 없다. 동작은 보존된다(행을 갱신하는 순서만 바뀜). 커밋: eaa293c743.
- 회귀 시험: `RefundOperationRowLockIntegrationTest`(PostgreSQL 18.6 Testcontainers, 실제 서비스 호출, `OwnerLockRegistry` 우회). 같은 멱등 키로 환불 두 건을 장벽으로 동시에 250회 호출하고 어떤 호출도 예외 없이 끝나야 한다. 수정 적용 시 통과, 수정을 되돌린 변이 시 실패(250회 중 7회에서 `SQLSTATE 40P01`). 수정 뒤 같은 RR 구동: 시도 1500회, `40P01` 서버 로그 0줄, 집계 {R:OK=3000}.
- 다른 곳은 건드리지 않았다. finalize 쪽(`markRefundApplied`) 올림은 그대로 두었다. 두 트랜잭션 중 한쪽만 처음부터 `FOR UPDATE`를 잡으면 올림 쌍이 성립하지 않기 때문이다.

#### 양성 대조 (3b-2)

| 구동 | 조건 | n | 40P01 | 깨끗 | 기타 | 검출률 |
|---|---|---:|---:|---:|---:|---:|
| 실코드(저장소 메서드 호출) | 양성(반대 순서), 작업 창 20 ms | 200 | 197 | 3 | 0 | 0.985 |
| 실코드(저장소 메서드 호출) | 양성(반대 순서), 작업 창 없음 | 200 | 166 | 34 | 0 | 0.830 |
| 실코드(저장소 메서드 호출) | 음성(같은 순서), 작업 창 20 ms | 200 | 0 | 200 | 0 | - |
| 실코드(저장소 메서드 호출) | 양성(반대 순서), 작업 창 100 ms | 150 | 150 | 0 | 0 | 1.000 |
| 실코드(저장소 메서드 호출) | 양성(반대 순서), 작업 창 없음 | 150 | 147 | 3 | 0 | 0.980 |
| 실코드(저장소 메서드 호출) | 음성(같은 순서), 작업 창 100 ms | 150 | 0 | 150 | 0 | - |
| 복제(SQL만) | 양성(반대 순서), 작업 창 0 ms | 300 | 256 | 44 | 0 | 0.853 |
| 복제(SQL만) | 양성(반대 순서), 작업 창 5 ms | 300 | 274 | 26 | 0 | 0.913 |
| 복제(SQL만) | 양성(반대 순서), 작업 창 20 ms | 300 | 295 | 5 | 0 | 0.983 |
| 복제(SQL만) | 양성(반대 순서), 작업 창 100 ms | 300 | 299 | 1 | 0 | 0.997 |
| 복제(SQL만) | 음성(같은 순서), 작업 창 20 ms | 300 | 0 | 300 | 0 | - |
| 복제(SQL만) | 음성(같은 순서), 작업 창 0 ms | 300 | 0 | 300 | 0 | - |
| 복제 구동 안(지터·장벽·사고 시간만, 작업 창 따로 없음) | 양성 R 대 변이 | 240 | 203 | 37 | 0 | 0.846 |

- 실코드 20 ms 창: 197/200, 창 없음: 166/200 · 147/150, 창 100 ms: 150/150. 복제 구동(`ctl`)의 창별 검출: 0 ms 256/300, 5 ms 274/300, 20 ms 295/300, 100 ms 299/300.
- 검출률은 두 트랜잭션이 실제로 겹쳐야 오른다. 창이 없으면 한쪽이 끝난 뒤 다른 쪽이 시작하는 시도가 생겨 반대 순서여도 교착이 나지 않는다.

### 삭제한 파일의 내용 (영역 3a·3b)

원자료와 구동 스크립트는 저장소에 올리지 않았다. 아래가 재현에 필요한 전부다. 이 영역에서 바꾼 운영 코드는 `PaymentRefundRepository.updateResult` 한 곳이다.

#### 3a 균일성 시험

- 컨테이너: `postgres:18.6`, `fsync=off`, `shared_buffers=512MB`, 2 CPU·2 GB. 타이밍을 재는 시험이 아니라 개수를 세는 시험이다.
- 키는 **템플릿 12개 × 1,000,000개 = 12,000,000개**. 판매자 1,000개, 주문 10,000개, 캠페인 100개의 UUID 풀(`uuidv7()::text`)에서 뽑아 실제 형식으로 만들었다.

| 이름 | 생성 SQL(축약) |
|---|---|
| T1a 주문 id, 집중 | `uuidv7()::text` |
| T1b 주문 id, 1년에 분산 | `uuidv7(random()*31536000 * interval '1 second')::text` |
| T2 결제 시도 id | `gen_random_uuid()::text` |
| T3a 환불 키(반품) | `'order-refund:'\|\|주문\|\|':return-'\|\|gen_random_uuid()` |
| T3b 환불 키(순번) | `'order-refund:'\|\|주문\|\|':refund-'\|\|(g/10000)` |
| T4 지급 | `'settlement-payout:'\|\|uuidv7()` |
| T6a 구매자(UUID ref) | `캠페인\|\|':'\|\|gen_random_uuid()` |
| T6b 구매자(순번 ref) | `캠페인\|\|':buyer-'\|\|(g/100)` |
| T7a 구 용량조정 | `판매자\|\|':DEFAULT:SKU-'\|\|lpad(g,8,'0')\|\|':adj-'\|\|g` |
| T7b 구 이전 | `판매자\|\|':SKU-'\|\|lpad(g%5000,8,'0')\|\|':'\|\|gen_random_uuid()` |
| T8a·T8b 신 `encode` | `길이\|\|':'\|\|부분`을 부분마다 이어붙임 |

- 해시: `hashtextextended(key, 0)`(운영 SQL과 같은 호출)를 `bigint`로 저장. 하위 32비트(`h & 4294967295`)와 상위 32비트(`(h >> 32) & 4294967295`)로 자른 값이 같은 쌍의 개수를 템플릿별로 센다: `SELECT sum(c*(c-1)/2) FROM (SELECT <잘린 값> k, count(*) c FROM keys WHERE tpl=? GROUP BY 1) t`.
- 기대값 `E = n(n-1)/2 / 2^32 = 116.42`(n = 10^6), `z = (관측 - E)/sqrt(E)`, 판정 `|z| ≤ 4`.
- 64비트 충돌: 같은 `h`를 갖는 서로 다른 키 쌍을 템플릿별로, 그리고 12,000,000개 전체를 합쳐서 센다(`JOIN`으로 `count(DISTINCT key) > 1`인 그룹). 합친 기대값 `N(N-1)/2/2^64`.
- 보조 점검: 하위 16비트 65,536 칸 카이제곱(자유도 65,535), 비트 64개별 편향 z, 양성 대조(키 앞 12글자만 해시하면 32비트 충돌 통계가 4σ 밖으로 나가는지), 32비트 네이티브 `hashtext(key)` 충돌.
- 선행 시도: 같은 구동을 한 번 돌렸으나 컨테이너가 중간에 종료되어(호스트 도커 재시작) 합친 64비트 질의가 끝나지 못했다. 이 문서의 표는 컨테이너를 새로 만들어 처음부터 다시 돌린 결과다(키는 새로 뽑았으므로 선행 시도의 숫자와 다르다).

#### 3a 형식 언어 판정

- 언어 정의: UUID `[0-9a-f]{8}-[0-9a-f]{4}-V[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}`(V는 v7이면 `7`, v4이면 `4`, 형식 수준에서는 `[0-9a-f]`), 자유 문자열 `[ -~]+`(콜론 포함), 템플릿 10개(주문 id, 시도 id, 환불, 지급, sweep, 구매자 UUID ref, 구매자 자유 ref, 구 용량조정, 구 이전, `encode` 상위집합 `[0-9]+:[ -~]*`)를 `interegular` 0.3.3의 유한 오토마타 곱으로 모든 쌍 45개에 대해 교집합이 비었는지 계산했다.

#### 3b 실코드 구동

- Spring Boot 통합 시험 한 클래스. `PayPalIntegrationTestSupport`를 상속해 실제 서비스 빈(`OrderRefundService`, `SettlementService`, `SettlementPayoutService`, `PayoutDispatchApprovalService`, `PayoutDispatchWorker`, `InventoryService`)을 호출한다. DB는 Testcontainers `postgres:18.6`, 연결 옵션으로 앱의 실제 값 `lock_timeout=5000`, `statement_timeout=30000`, `idle_in_transaction_session_timeout=60000`을 건다. `deadlock_timeout`은 기본 1 s, `log_lock_waits=on`. 컨테이너 2 CPU·2 GB.
- 프로세스 안 `OwnerLockRegistry`(결제 소유자 단위 JVM 락)는 `@MockitoSpyBean`으로 우회했다. 여러 복제본이 동시에 도는 상황을 흉내 내려는 것이고, 이때 직렬화는 DB 락만 맡는다.
- 준비: 판매자 1, SKU 2(`k1`, `k2`), 결제 완료 주문 2건(각 1,500개)을 서비스 호출로 만들고 지급 프로필을 넣었다. PayPal 지급 API는 실제 Sandbox 응답 모양을 따르는 가짜 서버로 대체했다.
- 시도 한 번: 조합의 경로마다 스레드 하나를 만들고 `CyclicBarrier`로 맞춘 뒤, 스레드마다 `{0, 0, 20µs, 100µs, 500µs, 2ms, 6ms, 15ms}` 중 하나를 골라 `0~그 값` 균등 지연을 두고 시작한다. 같은 판매자·같은 결제 완료 주문·같은 SKU를 공유한다.
- 경로 구동: R = `OrderRefundService.refund`(1개 수량, RR의 절반은 같은 멱등 키를 공유), P = 무작위 변형 {지급 대기분 발송만(20%), 입금 기록 후 지급 요청·승인·발송(60%), 요청·승인·취소(20%)}, S = `reserveRefund` 후 `releaseRefundReservation` 또는 `recordRefund`(+`recordCapture`), I = 두 SKU `reserveAll`(순서 섞음) 후 `releaseAll` 또는 `claimAll`(+`restoreClaimed`).
- 시도마다 오류를 SQLSTATE로 분류(`SQLException` 원인 사슬 탐색: `40P01`, `55P03`, 기타). 서버 로그의 `deadlock detected`와 `due to lock timeout` 줄 수를 조합 전후로 센다. 조합마다 끝난 뒤 불변식 SQL 11개를 돌린다: 전표 차대변 합 0, 재고 풀 음수 없음·`initial_capacity = available + reserved + claimed`, `reserved`·`claimed`가 예약 행 합과 같음, 환불 수량 ≤ 주문 수량, 공급자 환불 합 ≤ 승인액, 환불 전표 중복 없음, 완료 환불마다 전표 정확히 1개, 복원 수량 = 완료 환불 수량, 확정 지급마다 지급 전표 정확히 1개, 미확정 지급 전표 없음.
- 양성 대조: 같은 DB 연결 설정에서 `OrderRefundRepository.lockIdempotencyKey`·`SettlementJournalRepository.lockSellerPayouts`를 실제로 호출하는 두 트랜잭션을 만들어 한쪽은 실제 환불 순서(`A_idem` → `A_sell`), 다른 쪽은 반대 순서(`A_sell` → `A_idem`)로 잡는다. 락 사이에 `pg_sleep`으로 작업 창을 둔 변형과 두지 않은 변형, 같은 순서 음성 대조를 각각 200회, 5개씩 병렬로 돌렸다.

- 재현·진단 구동: 같은 구동을 RR 조합만 1차 1,500회, 2차 700회로 다시 돌렸다(`QT_ONLY=RR`, 결제 완료 주문 2~3건 × 3,000개, 통제 구동 없음). 2차는 컨테이너에 `log_statement=all`, `log_line_prefix='%m [%p] x=%x '`를 주어, 교착 때 두 프로세스가 직전에 실행한 문장 순서를 서버 로그에서 읽었다. 두 호출이 같은 환불 작업을 가리켰는지는 `Parameters: $1 = '…'` 줄의 `refund_operation_id`를 대조해 판정했다(4건 모두 같음). RR 시도의 절반은 두 호출이 같은 멱등 키를 공유하고 나머지는 서로 다른 키다.
- 수정 검증 구동: 수정 적용 뒤 같은 RR 구동(1,500회, 같은 매개변수)을 한 번 더 돌렸다.

#### 3b 복제 구동(락 순서 복제)

- 실코드 구동은 호스트 부하(load1 약 30) 아래에서 환불 1건이 평균 1 s 안팎이라 R을 포함한 조합의 시도 수를 늘리기 어려웠다. 그래서 같은 락 순서를 **복제**한 Python 구동을 같은 지터·장벽으로 병렬 8프로세스로 함께 돌려 R 조합의 겹침 수를 채웠다. 복제는 위 표의 트랜잭션별 획득 순서를 그대로 옮긴 것이다. advisory 락은 운영과 같은 `SELECT pg_advisory_xact_lock(hashtextextended(?,0))`에 같은 키 문자열 형식을 쓰고, 행 락은 시도별로 만든 단순 테이블의 행에 `SELECT ... FOR UPDATE`, `UPDATE`, `FOR UPDATE SKIP LOCKED`를 건다. 락 사이에는 `{0,0,0,0.5,2,5} ms` 중 무작위 사고 시간을 둔다.
- 복제는 실제 SQL·제약·불변식을 포함하지 않는다. 락 식별과 순서만 같다. 불변식은 실코드 구동에서만 확인했다.
- 복제 양성 대조는 `ctl` 구동(`R`과 반대 순서 변이, 작업 창 0·5·20·100 ms 각 300회, 병렬 8)이다. 키는 운영 형식(`order-refund:<UUID>:ctl`, `settlement-payout:<UUID>`)을 썼고 연결 옵션은 앱과 같다.

### 가설 결과 (영역 3a·3b)

| # | 결과(수치, n) | 반증 발동 여부 | 해석 |
|---|---|---|---|
| 3a-1 | 형식 오토마타 곱 45쌍. 형식 수준(UUID 버전 무관): 교집합 5쌍·서로소 40쌍. 생성기 수준(주문 v7, 시도 v4): 교집합 4쌍·서로소 41쌍. 후보 쌍 주문 id×결제 시도 id는 형식 수준 교집합 있음, 생성기 수준 공집합. `JdkIdGenerator` n=100,000 전부 v4(v7 0). | 문자 그대로는 **발동** ✗(형식 수준에서 같은 언어). 생성기 수준에서는 미발동 ✓ | 가설의 "형식"에 버전 니블이 빠져 있어 잘못 명세됐다. 실제 값 집합은 서로소라 의미 충돌은 없다. 의미 충돌이 있는 쌍은 구 구분자 재고 키 둘뿐이고 이미 해결돼 있다. |
| 3a-2 | n=10^4에서 `P = 2.711e-12`. 실제 n ≤ 1,600(연결 400 × 4)에서 6.935e-14. | 발동 없음 ✓ (기준 1e-11 미만) | 충돌은 직렬화만 늘리고 정확성을 깨지 않는다. 연간 기대 발생 T3 1.97e-06회(가정). |
| 3a-3 | 32비트 잘림 검정 24개(템플릿 12 × 하위·상위, 템플릿당 n=1,000,000): 4σ 밖 0개, 최대 `\|z\|` 3.11. 64비트 충돌 쌍: 템플릿별 0, 12,000,000개 합산 0(기대 3.903e-06). 카이제곱 12개·비트 편향 768개: 4σ 밖 0개. | 발동 없음 ✓ | `hashtextextended(key,0)`는 이 12개 실제 형식에서 균일하다는 가정을 기각하지 못했다. 약한 입력 양성 대조는 2/3에서 4σ 밖이었다(나머지 1개는 앞 12글자에 무작위 44비트가 남아 균일이 기대값). |
| 3b-1 | 실코드 6780회(경로 실행 14760건): `40P01` 1건(RR, 같은 키 공유 시도), `55P03` 0건, 불변식 위반 0건. RR 재현 구동 1500회에서 15줄, 700회에서 4줄. 복제 5920회 `40P01` 0건. | **발동** ✗ (`40P01` 1건 이상) | 교착이 있었다. 같은 멱등 키 동시 환불에서 결제 환불 작업 행의 락 모드 올림이 겹친다(위 근본 원인). 정적 락 순서 추출은 advisory·행 락 **획득 순서**만 봤고 같은 행에서 **락 모드를 올리는** 경로는 보지 못했다. 복제 구동은 FK 락 모드를 재현하지 않아 이 교착을 놓쳤다. |
| 3b-2 | 실코드 반대 순서 양성 대조: 창 20 ms 197/200, 창 없음 166/200 · 147/150, 창 100 ms 150/150. 복제 구동 `ctl`: 0 ms 256/300, 5 ms 274/300, 20 ms 295/300, 100 ms 299/300. 같은 순서 음성 대조: 40P01 0건/350회(실코드), 0건/600회(복제). | 사전 등록 구동(창 20 ms)은 197/200 = 98.5%라 99% 미만 → **발동** ✗. 창 100 ms는 실코드 150/150, 복제 299/300로 99% 이상 ✓ | 20 ms 창만으로는 99%를 못 채웠다. 3/200은 두 트랜잭션이 겹치지 않고 끝났다. 원인은 측정하지 않았다(호스트 부하 load1 약 30에서 스레드 시작이 창보다 더 엇갈렸을 가능성이 있다). 사전 등록 규칙대로라면 3b-1 결과는 폐기 대상이다. 창을 100 ms로 키운 대조는 통과했고, 그 창 이상으로 겹치는 락 구간에서는 시험이 교착을 잡는다. 20 ms 이하로만 겹치는 구간의 검출은 83~98%다. 그 범위의 0건은 "없음의 증명"이 아니다. |

### 한계 (영역 3a·3b)

- 실코드 구동은 호스트 load1 약 30, 프로세스 우선순위 최저(`nice 19`) 상태에서 돌렸다. 개수를 세는 시험이라 타이밍을 해석하지 않았다. 측정 잠금은 잡지 않았다.
- 실코드 시도 수는 R을 포함한 조합이 R이 없는 조합보다 적다(조합별 표). 5,000회 이상은 실코드만으로 채웠다. 복제에는 불변식 검사가 없다.
- 프로세스 안 `OwnerLockRegistry`를 우회했다. 네 경로를 한 JVM에서 겹치므로 복제본 여러 개가 겹치는 상황의 대리일 뿐이다.
- P 경로의 987건은 업무 예외로 중단됐다(락 획득 뒤). 경로별 성공 실행 수는 R 1279, P 3423, S 4560, I 4510이다.
- 복제는 락 식별과 순서만 같고 실제 SQL·제약·인덱스 락(유니크 인덱스 대기)을 재현하지 않는다. 유니크 키 충돌 대기는 실코드 구동에서만 겹친다.
- 양성 대조는 창 길이에 민감하다(창 없음 83.0%, 5 ms 91.3%, 20 ms 98.3~98.5%, 100 ms 99.7~100%). 짧게 겹치는 구간의 교착은 놓칠 수 있다.
- 위치 서비스를 분리 배포한 구성(재고 DB가 따로 있는 구성)에서의 락 공간은 시험하지 않았다. 단일 DB 구성이 더 나쁜 쪽이어서 그쪽 기준으로 판정했다.
- 형식 언어 판정은 자유 문자열 필드를 인쇄 가능 ASCII 전체로 근사했다. 실제 허용 문자 집합이 더 좁으면 교집합이 줄 수는 있어도 늘지 않는다.
- 해시 균일성은 PostgreSQL 18.6 한 버전, 12개 형식 템플릿에서만 봤다. 키의 실제 분포(판매자·주문 수)는 풀 크기로 근사했다.
- advisory 키를 바꾸지 않았으므로 롤링 배포 혼재 시험은 쓰지 않았다. 락 순서 수정의 PostgreSQL 통합 시험·변이 확인은 위 회귀 시험이다.
- 복제 구동은 락 모드 올림(UPDATE 뒤 `FOR UPDATE`, FK 갱신의 `FOR KEY SHARE` 뒤 `FOR UPDATE`)을 재현하지 않아 이 교착을 놓쳤다. 실코드 구동만 잡았다.
- 복제 컨테이너·실코드 컨테이너의 `deadlock_timeout`은 기본 1 s다. 교착 검출 지연은 사전 점검에서 교착 시도 묶음 한 번이 약 1~1.4 s였다는 정도만 관측했고 측정하지 않았다.

### 주장 표 (영역 3a·3b)

| 주장 | 증거 | 확신도 |
|---|---|---|
| 운영 코드의 advisory 호출은 8개 파일 9줄(`pg_advisory_xact_lock` 7, `pg_try_advisory_lock` 1, `pg_advisory_unlock` 1)이다 | `git grep` 결과, 호출자 전수 확인 | 확인 |
| 주문 id는 v7, 결제 시도 id는 v4라 두 키 문자열은 같을 수 없다 | `PurchaseOrder`의 `VERSION_7`, `JdkIdGenerator` 10만 개 v4 100,000 | 확인 |
| 구 구분자 재고 키 둘은 같은 문자열을 만들 수 있고 이미 `encode` + 이중 잠금으로 막혀 있다 | 오토마타 곱, `InventoryAdvisoryLockKeyTest` | 확인 |
| `hashtextextended(key,0)`는 12개 실제 형식에서 균일하다(4σ 안) | 12,000,000개, 검정 24 + 12 + 768개 | local-only(에이전트 보고) |
| 동시 보유 키 n ≤ 1,600에서 64비트 충돌 확률은 7e-14 이하다 | 생일 한계 계산, 연결 수는 설계 문서 값 | 확인(연결 수는 문서 가정) |
| 네 경로의 advisory·행 락 **획득 순서**에는 순환이 없다 | 코드에서 추출한 표 | 확인(획득 순서만; 같은 행의 락 모드 올림은 추출에 없었고 교착을 만들었다) |
| 실코드 겹침 6780회에서 `40P01` 1건(RR), `55P03` 0건, 불변식 위반 0건이다 | 시도별 SQLSTATE 분류, 서버 로그 줄 수, 불변식 11개 | local-only(에이전트 보고) |
| 복제 겹침 5920회에서 `40P01`·`55P03`가 0건이다 | 8프로세스 구동 집계 | local-only(에이전트 보고) |
| 반대 순서 변이는 창 100 ms에서 99% 이상, 창 20 ms에서 98~98.5% 검출된다 | 실코드 150/150 · 197/200, 복제 299/300 · 295/300 | local-only(에이전트 보고) |
| 창이 20 ms 이하인 구간의 교착은 놓칠 수 있다 | 창 없음 166/200 · 147/150, 5 ms 274/300, 0 ms 256/300 | local-only(에이전트 보고) |
| 같은 멱등 키 동시 환불에서 결제 환불 작업 행의 락 모드 올림이 교착(40P01)을 만든다 | 재현 구동 2회(1500회 중 15줄, 700회 중 4줄), 서버 로그 문장 순서, 같은 `refund_operation_id` | local-only(에이전트 보고) |
| `FOR KEY SHARE`(FK)가 교착의 한쪽 락이다 | 문장 순서·FK 정의에서 도출, `pg_locks` 관측 없음 | 재현 못 함(원인 미확정) |
| `updateResult`를 FOR UPDATE 먼저로 바꾸면 회귀 시험이 통과하고 되돌리면 실패한다 | 수정 통과, 변이 실패 | local-only(에이전트 보고) |

### 결정 (영역 3a·3b)

- **D1(advisory 키 통합): 현행 유지.** 의미 충돌이 있는 쌍은 구 구분자 재고 키 둘뿐이고 이미 `encode` + 구→신 고정 순서 이중 잠금으로 막혀 있다. 주문 id(v7)와 결제 시도 id(v4)는 값 집합이 서로소다. 실제 n ≤ 1,600에서 충돌 확률은 7e-14 이하이고 12,000,000개 키에서 64비트 충돌은 0쌍이다. 통합 키 형식은 줄이는 것이 없고(태그 방식은 확률을 키운다) 구·신 이중 잠금 전환과 혼재 시험 비용만 만든다. 코드를 바꾸지 않았다.
- **락 순서: 교착 발견, 최소 수정 채택.** 무작위 겹침 12700회 중 같은 키 동시 환불에서 `40P01`(실코드 1건 + RR 재현 구동 15·4줄). 수정은 `PaymentRefundRepository.updateResult` 한 곳(UPDATE 앞 `FOR UPDATE`)이다. 기본 동작은 보존되고, PostgreSQL 통합 시험 `RefundOperationRowLockIntegrationTest`가 수정 통과, 수정을 되돌린 변이 실패이다. 다른 영역 통합 시험은 이 문서 작성 시점에 돌리지 않았다(상위가 전체 검사를 돌린다).
- 3b-2는 문자 그대로 발동했다(창 20 ms에서 197/200). 창 100 ms 대조는 통과했다(실코드 150/150). 이 교착은 실제 서비스 겹침에서 RR 시도당 0.6~1.0%(재현 구동 두 번)로 드러났다.
- 롤백: `PaymentRefundRepository.updateResult`에서 `SELECT 1 … FOR UPDATE` 문장 한 개를 지우면 된다(마이그레이션·설정 변경 없음, 키·스키마 불변). 되돌리면 같은 키 동시 환불에서 교착이 다시 생기고, 회귀 시험이 그것을 잡는다.
- 뒤집을 증거: D1 항목 둘. 새 경로가 같은 행에서 약한 락(UPDATE·FK 갱신)을 쥔 채 `FOR UPDATE`로 올리면 같은 교착이 다시 생기므로 그 경로 포함으로 이 순열 시험을 다시 돌린다.

## 영역 3c 결과: SERIALIZABLE 대 advisory 락 (판매자 지급 가능액 예약, D4)

### 가설별 결과

| # | 결과(수치, n) | 반증 발동 여부 | 해석 |
|---|---|---|---|
| 3c-1 | 초과 예약 라운드 / 라운드(방식 A·B 합): 0 / 13109. 칸별(초과 라운드/라운드, B는 포기 요청 수 포함): S1-A: 0/2832; S1-B: 0/2277, 포기 0; S1-C: 2000/2000; S2-A: 0/2000; S2-B: 0/2000, 포기 2; S2-C: 2000/2000; S3-A: 0/2000; S3-B: 0/2000, 포기 1; S3-B2: 1974/2000; S3-C: 1881/2000 | 미발동 | A·B 모두 초과 예약 0건이라 반증 미발동(가설 유지). 같은 표의 대조 C(락 없는 READ COMMITTED)는 S1·S2는 전 라운드, S3은 대부분의 라운드에서 한도를 넘겨(예: S1 최대 12,000,000원 대 한도 1,000,000원) 주입한 경쟁이 실제로 문다는 것(시험 검출력)을 보인다. B는 불변식을 지키지만 재시도를 소진하면 요청이 포기된다(칸별 `포기`). 가설 문구는 "초과 예약 0건"만 요구하므로 B의 포기·중단은 이 가설에서 반증이 아니고 3c-2의 근거다. |
| 3c-2 | 같은 판매자 16 클라이언트(hot, A n=3, B n=3, 오염 아닌 run): 처리량 A 235.7/s, B 82.8/s (B/A 0.351), `40001` 중단/커밋 8.77회, 중단/시도 89.8%, 첫 시도 실패율 90.1%, 포기 1534건(요청 5258건 중) | 처리량 90% 미만, `40001` 비율 5% 초과 → 발동 | 반증 조건이 둘 다 발동했다(현행 유지). 처리량은 A의 0.351배(run별 범위는 아래 B/A 표, 어느 run도 0.9에 못 미침)이고 `40001` 중단이 커밋당 8.77회로, 요청 대부분이 첫 시도에서 중단(첫 시도 실패율 90.1%)되며 재시도 10번을 소진해 포기된 요청이 1534건이다(A는 포기 0). 경쟁이 약해져도 B가 A를 못 넘는다: 1 클라이언트 B/A 0.869(중단 0, 순수 SERIALIZABLE 비용), 4 클라이언트 0.770. 칸별 n은 표의 n열(hot 16만 3회, 나머지는 1회)이다. |

### 부가 관찰 (가설 밖, 같은 시나리오 표에서)

- 부분 도입(방식 B2: 지급 `request`만 SERIALIZABLE+재시도·락 없음, 환불 `reserveRefund`는 현행 락+READ COMMITTED) 시나리오 S3은 초과 예약 라운드 1974/2000(최대 대기 지급+보류 1,400,000원 대 한도 1,000,000원)이다. 락 없는 방식 C의 1881/2000과 같은 부류의 실패다. SERIALIZABLE은 그 불변식을 건드리는 경로가 모두 SERIALIZABLE일 때만 지킨다(PostgreSQL 13.2.3: "any set of successfully committed concurrent Serializable transactions", 환불 쪽이 READ COMMITTED이면 서로의 읽기-쓰기 의존이 추적되지 않음). 코드에서 `lockSellerPayouts`를 부르는 14곳과 그 바깥 트랜잭션(응용 계층 3개 클래스의 `@Transactional` 18개)을 함께 바꿔야 한다.
- 논리적 경쟁이 없는(own: 클라이언트마다 다른 판매자) 16 클라이언트에서도 B는 중단한다. 잠금 안 본표(n=1)는 커밋당 2.02회, 포기 62건이고, 구조 칸(잠금 밖, n=1)은 커밋당 2.80회, 포기 42건이다. 구조 칸에서 요청 경로의 `findByIdempotencyKey`만 빼면 3.33회(개선 없음), `findByIdempotencyKey`와 `findBySellerAndPeriod`를 함께 빼면 0.17회(포기 0건)다(각 n=1). pg_locks 표본의 SIRead 대상이 전부 페이지·튜플 단위(인덱스 페이지 포함)라서 서로 다른 판매자의 읽기와 삽입이 같은 인덱스 페이지에서 겹친 거짓 충돌로 보이지만, 페이지별 충돌 그래프는 확인하지 못했다. 절제에서도 0.17은 5%를 넘는다.
- `max_pred_locks_per_relation`을 기본(-2, 즉 `max_pred_locks_per_transaction` 64의 절반=32페이지)에서 1,000으로 올려 relation 승격을 막은 구조 칸(own 16, n=1)은 커밋당 3.01회로 줄지 않았다(SIRead 표 최대 4,409개, 기본은 127개). 거짓 충돌의 원인이 relation 승격이라는 설명은 이 칸에서 지지되지 않는다.

### 3c 방법

- 대상 경로: 판매자 지급 요청 `SettlementPayoutServiceImpl.request`. `lockSellerPayouts`를 부르는 곳은 서비스 코드 14곳(지급 서비스 11, 정산 서비스 3)이며 모두 같은 판매자 불변식(대기 지급 + 환불 보류 ≤ 지급 가능액)을 지킨다. 코드에서 뽑은 SQL 순서: 락 → `findByIdempotencyKey` → `findBySellerAndPeriod` → `sellerBalance`(원장 합) → `pendingAmount`(지급) → `pendingAmount`(환불 보류) → `INSERT … ON CONFLICT DO NOTHING RETURNING` → `find`. 환불 경로 `reserveRefund`는 락 → `find` → 보류 INSERT → `find` → `cancelUnsentForRefund`. 정확한 문장은 `삭제한 파일의 내용`에 있다.
- 방식: A = advisory 락 + READ COMMITTED(현행). B = SERIALIZABLE, 락 없음, `40001`/`40P01`에서 최대 10번 재시도(지연 0~min(2·2^n, 20) ms 무작위), 이 경로에 참여하는 모든 쪽이 SERIALIZABLE. C = READ COMMITTED, 락 없음(변이 대조: 초과 예약이 나와야 시험이 유효). B2 = 지급 쪽만 SERIALIZABLE+재시도·락 없음, 환불 쪽은 현행(락+READ COMMITTED) 그대로(부분 도입).
- 정확성 시나리오(락 없이, nice 19): S1 = 40 클라이언트가 지급 가능액 1,000,000원의 30%(300,000원)를 각각 요청, 합 ≤ 1,000,000이면 3건만 성공해야 함. S2 = 8 클라이언트가 실제 모양(전액 쓸어 담기, 기간 서로 다름)으로 요청, 1건만 성공해야 함. S3 = 지급 1건(읽기와 삽입 사이에 `pg_sleep` 2 ms 주입)과 환불 보류 400,000원 1건(0~4 ms 무작위 지연)이 겹침, 불변식은 대기 지급 + `RESERVED` 보류 ≤ 1,000,000.
- 처리량 시나리오(측정 잠금 안, 한 번의 보유): 같은 판매자(hot) 클라이언트 1/4/16/32, 판매자별 따로(own) 16. 각 클라이언트는 닫힌 고리로 100원을 예약·커밋하고 하네스가 곧바로 취소해 잔액을 되돌린다. 구간 15 s(준비 1.5 s는 표본에서 제외). 측정 잠금이 다른 에이전트에게 계속 잡혀 있어 내 보유를 5분으로 제한했고, 그 안에서 결정 칸(hot 16)을 A·B 3회씩 먼저 돌린 뒤 나머지 칸을 1회씩 시간이 되는 만큼 돌렸다. 돌지 못한 칸은 표의 "예산 때문에 시작하지 않은 run"에 있다.

### 주입 경쟁 정확성 (부하와 무관, 잠금 없이 nice로 실행; shard가 여럿이면 합산)

| 시나리오 | 방식 | shard | 라운드 | 스레드 | 초과 예약 라운드 | 과소 예약 라운드 | 최대 (대기 지급+보류) | 상태 건수 | 40001 | 40P01 | 시도 | 라운드 p50 ms(범위) | 라운드 p99 ms(최대) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| S1 | A | 14 | 2832 | 40 | 0 | 0 | 900000 | {"reserved": 8496, "rejected": 104784} | 0 | 0 | 113280 | 726.0-6067.79 | 12440.37 |
| S1 | B | 4 | 2277 | 40 | 0 | 0 | 900000 | {"rejected": 84249, "reserved": 6831} | 223917 | 0 | 314997 | 734.89-837.96 | 2622.8 |
| S1 | C | 1 | 2000 | 40 | 2000 | 0 | 12000000 | {"reserved": 68075, "rejected": 11925} | 0 | 0 | 80000 | 45.52-45.52 | 327.45 |
| S2 | A | 1 | 2000 | 8 | 0 | 0 | 1000000 | {"rejected": 14000, "reserved": 2000} | 0 | 0 | 16000 | 79.07-79.07 | 283.4 |
| S2 | B | 1 | 2000 | 8 | 0 | 0 | 1000000 | {"rejected": 13998, "reserved": 2000, "gave_up": 2} | 14110 | 0 | 30108 | 18.23-18.23 | 82.43 |
| S2 | C | 1 | 2000 | 8 | 2000 | 0 | 8000000 | {"reserved": 15815, "rejected": 185} | 0 | 0 | 16000 | 13.21-13.21 | 133.39 |
| S3 | A | 1 | 2000 | 2 | 0 | 0 | 1000000 | {"reserved": 4000} | 0 | 0 | 4000 | 17.44-17.44 | 64.06 |
| S3 | B | 1 | 2000 | 2 | 0 | 0 | 1000000 | {"reserved": 3999, "gave_up": 1} | 2190 | 0 | 6189 | 39.27-39.27 | 158.88 |
| S3 | B2 | 1 | 2000 | 2 | 1974 | 0 | 1400000 | {"reserved": 4000} | 0 | 0 | 4000 | 18.21-18.21 | 86.52 |
| S3 | C | 1 | 2000 | 2 | 1881 | 0 | 1400000 | {"reserved": 4000} | 0 | 0 | 4000 | 16.42-16.42 | 67.39 |

### 처리량·지연 본표 (오염 아닌 run만, 값은 평균 [min, max])

| 프로필 | 클라이언트 | 방식 | n | 예약/s | p50 ms | p95 ms | p99 ms | 중단/시도 | 중단/커밋 | 성공당 재시도 | 첫 시도 실패율 | 포기(합) | 거절(합) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| hot | 1 | A | 1 | 211.5 [211.5, 211.5] | 3.8 [3.8, 3.8] | 6.1 [6.1, 6.1] | 9.9 [9.9, 9.9] | 0.0000 [0.0000, 0.0000] | 0.0000 [0.0000, 0.0000] | 0.0000 [0.0000, 0.0000] | 0.0000 [0.0000, 0.0000] | 0 | 0 |
| hot | 1 | B | 1 | 183.8 [183.8, 183.8] | 4.1 [4.1, 4.1] | 9.1 [9.1, 9.1] | 13.5 [13.5, 13.5] | 0.0000 [0.0000, 0.0000] | 0.0000 [0.0000, 0.0000] | 0.0000 [0.0000, 0.0000] | 0.0000 [0.0000, 0.0000] | 0 | 0 |
| hot | 4 | A | 1 | 297.7 [297.7, 297.7] | 12.6 [12.6, 12.6] | 15.4 [15.4, 15.4] | 17.7 [17.7, 17.7] | 0.0000 [0.0000, 0.0000] | 0.0000 [0.0000, 0.0000] | 0.0000 [0.0000, 0.0000] | 0.0000 [0.0000, 0.0000] | 0 | 0 |
| hot | 4 | B | 1 | 229.4 [229.4, 229.4] | 3.8 [3.8, 3.8] | 70.1 [70.1, 70.1] | 121.2 [121.2, 121.2] | 0.5657 [0.5657, 0.5657] | 1.3025 [1.3025, 1.3025] | 1.2688 [1.2688, 1.2688] | 0.2485 [0.2485, 0.2485] | 116 | 0 |
| hot | 16 | A | 3 | 235.7 [227.3, 249.5] | 59.6 [58.4, 60.7] | 111.6 [82.7, 140.9] | 173.8 [112.5, 224.8] | 0.0000 [0.0000, 0.0000] | 0.0000 [0.0000, 0.0000] | 0.0000 [0.0000, 0.0000] | 0.0000 [0.0000, 0.0000] | 0 | 0 |
| hot | 16 | B | 3 | 82.8 [76.5, 92.0] | 81.1 [66.5, 91.0] | 230.2 [208.8, 245.7] | 277.9 [245.1, 313.1] | 0.8976 [0.8953, 0.8991] | 8.7680 [8.5500, 8.9102] | 8.3556 [8.1464, 8.4943] | 0.9011 [0.8936, 0.9057] | 1534 | 0 |
| hot | 32 | A | 1 | 162.9 [162.9, 162.9] | 183.9 [183.9, 183.9] | 284.4 [284.4, 284.4] | 368.1 [368.1, 368.1] | 0.0000 [0.0000, 0.0000] | 0.0000 [0.0000, 0.0000] | 0.0000 [0.0000, 0.0000] | 0.0000 [0.0000, 0.0000] | 0 | 0 |
| own | 16 | A | 1 | 1157.7 [1157.7, 1157.7] | 10.5 [10.5, 10.5] | 21.4 [21.4, 21.4] | 32.4 [32.4, 32.4] | 0.0000 [0.0000, 0.0000] | 0.0000 [0.0000, 0.0000] | 0.0000 [0.0000, 0.0000] | 0.0000 [0.0000, 0.0000] | 0 | 0 |
| own | 16 | B | 1 | 253.5 [253.5, 253.5] | 33.0 [33.0, 33.0] | 182.2 [182.2, 182.2] | 268.8 [268.8, 268.8] | 0.6690 [0.6690, 0.6690] | 2.0213 [2.0213, 2.0213] | 2.0050 [2.0050, 2.0050] | 0.6134 [0.6134, 0.6134] | 62 | 0 |

### B/A 처리량 비율 (본표 run, 같은 rep끼리)

| 프로필 | 클라이언트 | n | B/A 비율 (run별) | 평균 | A 범위와 B 범위가 겹치는가 |
|---|---|---|---|---|---|
| hot | 1 | 1 | 0.869 | 0.869 | 겹치지 않음 |
| hot | 4 | 1 | 0.770 | 0.770 | 겹치지 않음 |
| hot | 16 | 3 | 0.332, 0.405, 0.320 | 0.352 | 겹치지 않음 |
| own | 16 | 1 | 0.219 | 0.219 | 겹치지 않음 |

### 오염 run 표 (load1 > 12 또는 queuetx 외 라벨 컨테이너가 시작·끝 status에 있음; 헤드라인 수치에 쓰지 않음)

해당 run 없음.

### 오염 run의 B/A 비율

| 프로필 | 클라이언트 | n | B/A 비율 (run별) | 평균 | A 범위와 B 범위가 겹치는가 |
|---|---|---|---|---|---|

### 예산 때문에 시작하지 않은 run

hot32Br1

### run별 요약 (전부)

| label | 예약/s | 커밋 | 시도 | 40001 | 40P01 | 포기 | load1 시작/끝 | 라벨 시작/끝 | 오염 |
|---|---|---|---|---|---|---|---|---|---|
| hot16Ar1 | 230.4 | 3456 | 3456 | 0 | 0 | 0 | 10.0/9.41 | queuetx/queuetx | False |
| hot16Br1 | 76.47 | 1147 | 11367 | 10220 | 0 | 477 | 9.41/8.72 | queuetx/queuetx | False |
| hot16Ar2 | 227.33 | 3410 | 3410 | 0 | 0 | 0 | 8.72/9.37 | queuetx/queuetx | False |
| hot16Br2 | 92.0 | 1380 | 13179 | 11799 | 0 | 557 | 9.37/8.57 | queuetx/queuetx | False |
| hot16Ar3 | 249.47 | 3742 | 3742 | 0 | 0 | 0 | 8.57/7.63 | queuetx/queuetx | False |
| hot16Br3 | 79.8 | 1197 | 11783 | 10586 | 0 | 500 | 7.63/9.73 | queuetx/queuetx | False |
| hot4Ar1 | 297.73 | 4466 | 4466 | 0 | 0 | 0 | 9.73/7.78 | queuetx/queuetx | False |
| hot4Br1 | 229.4 | 3441 | 7923 | 4482 | 0 | 116 | 7.78/6.92 | queuetx/queuetx | False |
| own16Ar1 | 1157.67 | 17365 | 17365 | 0 | 0 | 0 | 6.92/9.5 | queuetx/queuetx | False |
| own16Br1 | 253.47 | 3802 | 11487 | 7685 | 0 | 62 | 9.5/11.27 | queuetx/queuetx | False |
| hot1Ar1 | 211.47 | 3172 | 3172 | 0 | 0 | 0 | 11.27/9.56 | queuetx/queuetx | False |
| hot1Br1 | 183.8 | 2757 | 2757 | 0 | 0 | 0 | 9.56/8.52 | queuetx/queuetx | False |
| hot32Ar1 | 162.93 | 2444 | 2444 | 0 | 0 | 0 | 8.52/9.13 | queuetx/queuetx | False |

### SIRead 락 구조 칸 (구조 확인용, 처리량 주장에 쓰지 않음; 측정 잠금 밖)

| label | 프로필 | 클라이언트 | 설정 | 커밋 | 중단/커밋 | SIRead 총합 최대 | 대상별 최대(상위) | 표본 수 | 포기 |
|---|---|---|---|---|---|---|---|---|---|
| structOwn16def | own | 16 | 기본(64 / -2 / 2) | 708 | 2.8008 | 127 | page:settlement_entry_seller_account_side_idx=32, page:settlement_entry=23, page:settlement_payout_key_unique=19, page:settlement_payout_seller_period_active_unique=18, page:ix_settlement_payout_seller_state=18 | 66 | 42 |
| structHot16def | hot | 16 | 기본(64 / -2 / 2) | 307 | 11.9674 | 166 | page:settlement_entry_seller_account_side_idx=75, page:settlement_entry=27, page:ix_settlement_payout_seller_state=22, page:settlement_payout_key_unique=17, page:settlement_payout_seller_period_active_unique=16 | 66 | 204 |
| structOwn16raised | own | 16 | max_pred_locks_per_relation=1000 | 861 | 3.0128 | 4409 | page:settlement_entry=3852, tuple:settlement_entry=504, page:settlement_entry_seller_account_side_idx=31, page:settlement_payout_key_unique=19, page:ix_settlement_payout_seller_state=18 | 64 | 42 |
| structOwn16skipKey | own | 16 | 경로에서 key 조회 생략(절제) | 1242 | 3.3253 | 209 | page:settlement_entry=144, page:settlement_entry_seller_account_side_idx=29, page:settlement_payout_seller_period_active_unique=18, tuple:settlement_entry=18, page:ix_settlement_payout_seller_state=18 | 66 | 102 |
| structOwn16skipKeyPeriod | own | 16 | 경로에서 key·period 조회 생략(절제) | 6619 | 0.1713 | 238 | page:settlement_entry=112, page:ix_settlement_payout_seller_state=62, page:settlement_entry_seller_account_side_idx=60, relation:settlement_entry=34, page:settlement_payout_pkey=34 | 66 | 0 |

### 한계 (사실)

- 호스트가 공유됐다. 처리량 칸(측정 잠금 안)의 `load1`은 6.9~11.3(오염 기준 12 이하), `atlasexp` 라벨 컨테이너는 `queuetx`뿐이었다. 그러나 라벨이 없어 `status`가 못 보는 컨테이너(`atlas-prodlike-*` 묶음 등)는 같이 돌았다. 보유 시작 표본에서는 각각 3~4% 이하였고, 보유 끝 표본(10:19:52)에서 `buildx_buildkit_pax-deploy0` 310%와 `atlas-prodlike-kafka-1` 70%가 보였다. 이 시점은 마지막 run(hot 32 A)과 겹치므로 그 run은 근거로 쓰지 않는다(본표에는 남아 있지만 B 짝이 없어 비율도 없음). 호스트 CPU 11코어, 컨테이너 VM 8코어, 측정 대상 컨테이너는 `--cpus 2`이고 클라이언트(Python 프로세스)도 같은 호스트에서 돈다.
- S1 정확성 칸과 구조 칸은 측정 잠금 밖에서 `load1` 약 20~34일 때 돌렸다(S2·S3·S1 방식 C는 07:18~09:01에 돌았고 그때 `load1`은 기록하지 않았다). 정확성(초과 예약 수)은 부하와 무관하다고 보고 그대로 썼지만, 중단 횟수는 부하에 따라 변한다(hot 16 중단/커밋: 구조 칸 11.97, 잠금 안 본표 8.77). 구조 칸 수치는 처리량·비율 주장에 쓰지 않았다.
- 측정 잠금을 다른 에이전트가 계속 잡고 있어 내 보유는 4분 13초(상위 작업자 요청 5분 한도)였다. 그 안에서 hot 16만 3회(A·B), hot 4·hot 1·own 16은 1회, hot 32는 A 1회뿐이고 hot 32 B는 예산 때문에 돌리지 못했다. `atlas_big` 칸(큰 지급 표)도 돌리지 못했다.
- 처리량 칸의 예약은 100원 슬라이스 하나를 커밋하고 곧바로 취소하는 모양이다(실제 코드는 전액 쓸어 담기). 같은 판매자(hot) 프로필은 모든 클라이언트가 같은 지급 표 인덱스 구간에 쓰는 최악 경쟁이고, own 프로필은 논리적 경쟁이 없는 대조다. 운영의 실제 판매자별 요청 분포와 체크아웃 정산 기록(같은 `settlement_entry`에 INSERT)이 겹치는 혼합은 재현하지 않았다.
- 처리량을 잰 경로는 `request` 하나다. `lockSellerPayouts`를 부르는 14곳 중 나머지 13곳(상태 전이, 환불 보류, 환불 기록 등)은 경쟁 처리량을 재지 않았고, 정확성 시나리오는 지급 `request`와 `reserveRefund`만 다뤘다.
- 재시도 정책은 한 가지(최대 10번, 0~min(2·2^n, 20) ms 무작위 지연)만 시험했다. 지연 상한이나 횟수를 바꾸면 포기 건수와 처리량이 달라질 수 있다.
- Spring 쪽 재시도 래퍼 시제품(43줄)은 컴파일·실행하지 않았다. Python 하네스가 같은 동작(트랜잭션 전체 재실행)을 흉내 낸 것이고, 줄 수만 셌다.
- PostgreSQL 18.6 단일 노드, 복제본 없음, 컨테이너 기본 설정(위 `삭제한 파일의 내용`). 표의 구조 칸(SIRead 표본)은 `max_connections=300`(술어 락 표 크기 64×300)에서 돌렸고 처리량 칸은 기본 100이다.
- S1(40클라이언트) 방식 A·B 라운드는 호스트 부하 때문에 shard를 나눠 `DEADLINE`까지 돌렸다. 방식 A의 달성 라운드 수는 표의 S1 행에 있다.

### 주변 부하 (잠금 보유 시작·끝의 `docker stats` 표본, 측정 컨테이너 제외, CPU 3% 초과만)

```text
== ambient start 10:15:41 load: load averages: 11.03 11.67 16.00
atlas-prodlike-app1-1 3.65%
atlas-prodlike-app2-1 3.58%
atlas-prodlike-order-postgres-1 3.33%
atlas-prodlike-loc-b-postgres-1 3.22%
== ambient end 10:19:52 load: load averages: 9.13 10.01 14.10
buildx_buildkit_pax-deploy0 310.62%
atlas-prodlike-kafka-1 69.87%
```

### 주장 표

| 주장 | 증거 | 확신도 |
|---|---|---|
| 현행(advisory 락 + READ COMMITTED)은 주입 경쟁에서 초과 예약이 없다 | 정확성 표 방식 A: 라운드 6832(S1 2832, S2 2000, S3 2000), 초과 0 | 확인 |
| 주입한 경쟁은 실제로 문다(시험의 검출력) | 방식 C(락 없는 READ COMMITTED): S1 2000/2000, S2 2000/2000, S3 1881/2000 라운드 초과 | 확인 |
| SERIALIZABLE + 재시도(모든 참여 경로가 SERIALIZABLE)도 초과 예약은 없다 | 방식 B: 라운드 6277, 초과 0, 재시도 소진으로 포기된 요청 3건 | 확인 |
| 일부 경로만 SERIALIZABLE이면 불변식이 깨진다 | S3 방식 B2: 초과 1974/2000 라운드 | 확인(시나리오 1개, 환불 대 지급 한 쌍) |
| 같은 판매자 16 클라이언트에서 B는 A보다 처리량이 낮다 | 처리량 표 hot 16: A 235.7/s, B 82.8/s, B/A 0.351(A n=3, B n=3, 오염 아닌 run) | 확인(n=3, 호스트 load1 6.9~11.3, 한계 참조) |
| 같은 판매자 16 클라이언트에서 B의 `40001` 중단은 커밋당 8.77회, 시도당 89.8%이고 포기 1534건(요청 5258건 중) | 처리량 표 hot 16 B | 확인 |
| 논리적 경쟁이 없어도(own 16) B는 중단·포기한다 | 처리량 표 own 16 B: 커밋당 2.02회, 포기 62건; 구조 칸 절제(요청 경로에서 key·period 조회를 함께 제거하면 0.17, key만 제거하면 3.33) | 확인(구조 칸은 n=1) |
| 그 거짓 충돌이 어느 인덱스 페이지에서 나는지(원인) | pg_locks 표본의 SIRead 대상이 전부 페이지·튜플 단위이고 절제는 기간 조회 제거 시에만 중단을 줄임. 페이지별 충돌 그래프는 보지 않음 | 재현 못 함(원인 미확정) |
| 재시도 래퍼 인프라가 저장소에 없다 | `git grep`: `40001`/`SerializationFailure`/`CannotSerialize` 처리 0건, `RetryTemplate`/`@Retryable` 0건, 격리 수준 지정 1건(읽기 전용 REPEATABLE_READ), `55P03`/`40P01` 문자열이 나오는 줄 2개(모두 `GlobalExceptionHandler`의 응답 변환, 재시도 아님), `lockSellerPayouts` 호출 14곳(지급 11, 정산 3), 응용 계층 바깥 트랜잭션 3개 클래스 `@Transactional` 18개 | 확인 |

### 결정 (3c-2, D4)

**현행 유지**(advisory 락 + READ COMMITTED). SERIALIZABLE 채택은 네 조건을 모두 채워야 하는데 아래처럼 3개가 불충족이다.

| 채택 조건 | 측정값 | 충족 |
|---|---|---|
| 경쟁 16 클라이언트 처리량 ≥ A의 90% | A 235.7/s, B 82.8/s, B/A 0.351 (n: A 3, B 3) | 불충족 |
| `40001` 비율 ≤ 5% | 중단/커밋 8.77회, 중단/시도 89.8%, 첫 시도 실패율 90.1% | 불충족 |
| 초과 예약 0건 | 방식 B 라운드 6277 중 0건(포기 3건 별도) | 충족 |
| 새 경로당 재시도 래퍼가 늘지 않음 | 저장소에 `40001` 재시도 인프라 0건. 도입하면 `lockSellerPayouts` 14곳 모두와 응용 계층 바깥 트랜잭션(3개 클래스, `@Transactional` 18개)을 SERIALIZABLE로 바꾸고 각 최외곽에 재시도를 둬야 한다(시제품 43줄 래퍼 1개 + 호출부 변경). 일부만 바꾸면 S3 B2처럼 불변식이 깨진다 | 불충족 |

경쟁 정도별(같은 판매자, 1 / 4 / 16 클라이언트; 32는 B를 돌리지 못함) B의 중단/커밋: 0.00회 / 1.30회 / 8.77회. 포기(재시도 10번 소진) 건수: 0 / 116 / 1534(각 칸 요청 2757 / 3557 / 5258건 중). 처리량 B/A: 0.869 / 0.770 / 0.351. B의 p50은 hot 4에서 A보다 낮지만(3.8 대 12.6 ms) p95·p99는 높다(70.1/121.2 대 15.4/17.7 ms; 지연은 커밋된 요청만 센다). 논리적 경쟁이 없는 own 16에서도 B/A 0.219, 중단/커밋 2.02회.

**실패 경로(채택했다면)**: 재시도를 소진한 요청은 호출자에게 실패로 올라가고 불변식은 지켜진다(방식 B 초과 0건). 저장소의 `GlobalExceptionHandler`는 `PessimisticLockingFailureException`·`55P03`·`40P01`을 503 + `Retry-After`로 바꾸고 같은 `Idempotency-Key` 재시도를 안전하다고 적고 있다. `40001`이 어느 예외 타입으로 올라오는지는 Spring 경로에서 실행해 확인하지 못했다. 호출자가 재시도하지 않으면 그 요청은 사라진다.

**결정을 뒤집을 작업 부하 성질**: 이 표에서 B가 A에 가장 가까운 칸은 hot 1(B/A 0.869, 중단 0)과 hot 4(0.770)이고(각 n=1) 어느 칸도 0.9에 못 미쳤다. 뒤집으려면 (1) 같은 판매자 동시 요청이 사실상 1개이거나 (2) 가드가 읽는 집합이 인덱스 페이지 범위가 아니라 판매자별 한 행이어서 서로 다른 판매자가 같은 인덱스 페이지를 읽기 락으로 공유하지 않아야 하고 (3) hot 1에서도 보이는 SERIALIZABLE 기록 비용(처리량 0.869배)이 줄어야 한다. (1)이면 락도 경합하지 않아 얻는 것이 없고, (2)·(3)은 경로 재설계(읽기 집합 축소)가 먼저다. 이 표의 절제(기간·키 조회 제거)에서도 own 16 중단이 커밋당 0.17회로 5%를 넘으므로 조회 제거만으로는 모자란다(구조 칸, n=1).

**되돌리기**: 코드를 바꾸지 않았으므로 되돌릴 것이 없다(이 영역은 측정 결과만 올림).

### 삭제한 파일의 내용

하네스(Python 3 + psycopg 3, 호스트에서 컨테이너로 접속)는 일회성 구동 스크립트라 저장소에서 뺐다. 필요하면 아래 설명으로 다시 만들 수 있다. 파일은 로컬 작업 폴더에 남아 있다.

**SQL(저장소 클래스에서 그대로 옮김, 자리표시자 `%s`는 psycopg 형식):**

- `SQL_LOCK`

```sql
SELECT pg_advisory_xact_lock(hashtextextended(%s, 0))
```

- `SQL_BY_KEY`

```sql
SELECT payout_id, seller_id, period_start, period_end, amount_won, settled_amount_won, state,
 requested_at, sent_at, confirmed_at, cancelled_at FROM settlement_payout WHERE idempotency_key = %s
```

- `SQL_BY_PERIOD`

```sql
SELECT payout_id, seller_id, period_start, period_end, amount_won, settled_amount_won, state,
 requested_at, sent_at, confirmed_at, cancelled_at FROM settlement_payout
 WHERE seller_id = %s AND period_start = %s AND period_end = %s AND state NOT IN ('FAILED', 'CANCELLED')
```

- `SQL_BALANCE`

```sql
SELECT
 COALESCE(SUM(CASE WHEN account_code = 'SELLER_PAYABLE'
  THEN CASE WHEN entry_side = 'CREDIT' THEN amount_won ELSE -amount_won END ELSE 0 END), 0) AS payable,
 COALESCE(SUM(CASE WHEN account_code = 'SELLER_RESERVE'
  THEN CASE WHEN entry_side = 'CREDIT' THEN amount_won ELSE -amount_won END ELSE 0 END), 0) AS reserve,
 COALESCE(SUM(CASE WHEN account_code = 'PLATFORM_FEE'
  THEN CASE WHEN entry_side = 'CREDIT' THEN amount_won ELSE -amount_won END ELSE 0 END), 0) AS fee
 FROM settlement_entry WHERE seller_id = %s
```

- `SQL_PENDING_PAYOUT`

```sql
SELECT COALESCE(SUM(amount_won), 0) FROM settlement_payout
 WHERE seller_id = %s AND state IN (
 'REQUESTED', 'APPROVED', 'DISPATCHING', 'SENT', 'UNKNOWN', 'UNCLAIMED', 'NEEDS_REVIEW')
```

- `SQL_PENDING_REFUND`

```sql
SELECT COALESCE(SUM(amount_won), 0) FROM settlement_refund_reservation
 WHERE seller_id = %s AND status = 'RESERVED'
```

- `SQL_INSERT_PAYOUT`

```sql
INSERT INTO settlement_payout
 (seller_id, period_start, period_end, amount_won, state, idempotency_key, requested_at, updated_at)
 VALUES (%s, %s, %s, %s, 'REQUESTED', %s, %s, %s)
 ON CONFLICT (seller_id, period_start, period_end) WHERE state NOT IN ('FAILED', 'CANCELLED') DO NOTHING
 RETURNING payout_id
```

- `SQL_FIND_PAYOUT`

```sql
SELECT payout_id, seller_id, period_start, period_end, amount_won, settled_amount_won, state,
 requested_at, sent_at, confirmed_at, cancelled_at FROM settlement_payout WHERE payout_id = %s
```

- `SQL_REFUND_FIND`

```sql
SELECT refund_id, order_id, seller_id, amount_won, status, created_at, updated_at
 FROM settlement_refund_reservation WHERE refund_id = %s
```

- `SQL_REFUND_INSERT`

```sql
INSERT INTO settlement_refund_reservation (
 refund_id, order_id, seller_id, amount_won, status, created_at, updated_at)
 VALUES (%s, %s, %s, %s, 'RESERVED', %s, %s) ON CONFLICT (refund_id) DO NOTHING
```

- `SQL_CANCEL_UNSENT`

```sql
UPDATE settlement_payout SET state = 'CANCELLED', failure_code = 'REFUND_PREEMPTED',
 cancelled_at = %s, updated_at = %s WHERE seller_id = %s AND state IN ('REQUESTED', 'APPROVED')
```

- `SQL_CANCEL_ONE`

```sql
UPDATE settlement_payout SET state = 'CANCELLED', failure_code = %s, cancelled_at = %s, updated_at = %s
 WHERE payout_id = %s AND state IN ('REQUESTED', 'APPROVED')
```

**데이터**: 실제 스키마(`atlas-application` 마이그레이션 V1~V6, 새 마이그레이션 없음)를 `postgres:18.6` 컨테이너(`--cpus 2 --memory 2g`, 라벨 `atlasexp=queuetx`, 기본 설정: `shared_buffers=128MB`, `max_pred_locks_per_transaction=64`, `max_pred_locks_per_relation=-2`, `max_pred_locks_per_page=2`, 처리량 칸·S1 외 정확성 칸 `max_connections=100`, S1 shard 칸과 구조 칸 `max_connections=300`)에 적용한 뒤 템플릿 DB를 만들고 run마다 `CREATE DATABASE … TEMPLATE`로 복제했다.

- 성능 템플릿 `atlas`: `seller` 201행(핫 판매자 1 + 나머지 200), 정산 전표 캡처 51,240건(핫 1,240건, 나머지 판매자당 250건, 시간순으로 섞음)마다 항목 4행(`PSP_CLEARING` 차변 100,000 / `SELLER_PAYABLE` 대변 85,000 / `PLATFORM_FEE` 대변 10,000 / `SELLER_RESERVE` 대변 5,000), 지급 완료(`CONFIRMED`) 이력 지급 2,040건(핫 40, 나머지 10씩)과 지급 전표·`SELLER_PAYABLE` 차변 항목, 환불 보류 25건(핫 판매자, `POSTED` 20 / `RESERVED` 5). 결과: 핫 판매자 `settlement_entry` 5,000행(질의로 확인), 전체 207,000행. `ANALYZE` 후 사용.
- `atlas_big`: `atlas`에 `CONFIRMED` 지급 200,000건(판매자 200 × 1,000)을 더하고 `VACUUM (ANALYZE)`(지급 표 202,040행). 이 칸은 만들어 두었으나 짧은 측정 창에서 돌리지 않았다.
- 정확성 템플릿 `race_tmpl`: 판매자 3,000명(10000~12999)에 각각 `SELLER_PAYABLE` 대변 1,000,000원짜리 전표 1건. 라운드마다 판매자 하나를 새로 써서 라운드끼리 독립.

**정확성 하네스(`race.py`)**: 스레드 N개가 장벽 두 개로 한꺼번에 출발해 같은 판매자에게 같은 트랜잭션 본문을 실행하고, 라운드 끝에 별도 연결로 `SUM(amount_won)`(대기 상태 지급 `REQUESTED/APPROVED/DISPATCHING/SENT/UNKNOWN/UNCLAIMED/NEEDS_REVIEW`)과 `RESERVED` 환불 보류 합을 읽어 `대기 지급 + 보류 > 1,000,000`이면 초과 예약 라운드로 센다(S1·S2에서는 `< 900,000`/`< 1,000,000`이면 과소 예약). S1의 `pending`이 900,000 미만이면 "3건 성공" 기대가 어긋난 것이다. 환불 쪽 본문(`reserveRefund` 순서: 락 → `find` → INSERT → `find` → 미전송 지급 취소)은 S3에서만 쓴다. 40001/40P01 재시도 지연은 시도 `n`번째에 `uniform(0, min(2·2^n, 20) ms)`, 최대 10번 재시도 후 포기로 센다. S1은 `DEADLINE`(에폭 초)으로 shard마다 계획 라운드(A 4×500, B 2×1000, 이후 A 6×350)를 채우기 전에 멈추고 한 일(라운드 수)을 보고한다. S1은 한 프로세스(스레드 40개)가 한 데이터베이스를 쓰고 shard끼리 DB가 다르다.

**처리량 하네스(`perf.py`)**: 클라이언트마다 별도 프로세스(연결 2개: 트랜잭션용 1, 하네스 취소용 autocommit 1), 장벽 뒤 0.3 s에 동시 시작, 준비 1.5 s는 표본에서 제외, 이후 15 s 동안 닫힌 고리로 예약 트랜잭션을 반복. 예약 한 건은 `reserve_body`(requested=100원, 슬라이스 모양)이며 커밋하면 하네스가 즉시 `SQL_CANCEL_ONE`으로 취소해 잔액을 되돌린다(취소 시간은 지연에 넣지 않음). 지연은 첫 시도 시작부터 커밋까지(재시도 포함) `perf_counter`. 집계: `예약/s = 커밋 수 / 15`, 중단/시도 = (40001+40P01)/시도, 중단/커밋 = (40001+40P01)/커밋, 성공당 재시도 = (시도 − 커밋 − 거절 − 포기)/커밋, 첫 시도 실패율 = 첫 시도가 중단된 요청 수/(커밋+거절+포기), 백분위수는 정렬한 지연 목록의 `round(p/100·(n−1))` 번째. `--locks`는 0.25 s마다 `pg_locks`에서 `mode='SIReadLock'`을 `locktype, relation`별로 센다.

**측정 잠금 구동(`timing.py`)**: 잠금 한 번 보유 안에서, 짧은 창 모드(실제 사용)는 hot 16을 rep 1~3 × (A 다음 B)로 먼저 돌리고 이어서 hot 4, own 16, hot 1, hot 32를 각 1회(A 다음 B) 돌린다. 긴 창 모드(사용하지 않음)는 rep를 바깥, 칸(hot 16, 4, 1, 32, own 16)을 안쪽에 둔다. 시작 전 `load1 ≤ 10`을 짧은 창에서는 최대 15 s 기다리고, run마다 시작·끝에 `measure-lock.sh status`를 기록해 `load1 > 12` 또는 `queuetx` 아닌 라벨 컨테이너가 있으면 오염으로 표시한다. 예산(짧은 창 225 s, 보유 시작 후 새 run 시작 한도)을 넘으면 새 run을 시작하지 않고 건너뛴 run을 기록한다.

**구조 칸(`--locks`)**: 처리량 하네스를 측정 잠금 밖에서 own 16과 hot 16(B, 기본 설정), `max_pred_locks_per_relation=1000`으로 올리고 `pg_reload_conf()`한 own 16(B), 환경 변수로 요청 경로의 문장을 생략한 own 16(B: `findByIdempotencyKey`만 생략, `findByIdempotencyKey`와 `findBySellerAndPeriod` 둘 다 생략)에 각 1회, 15 s로 돌렸다. 0.25 s마다 `SELECT locktype, relation::regclass, count(*) FROM pg_locks WHERE mode = 'SIReadLock' GROUP BY 1, 2`의 최댓값을 대상별·총합으로 기록했다. 이 칸들은 처리량·비율 주장에 쓰지 않는다.

**복원**: 위 설명과 SQL로 다시 만들 수 있다. 구동 스크립트와 결과 JSON 줄은 저장소에 없다.

## 영역 4 결과: 두 DB 원자성, 2PC 시제품 대 현행 workflow (X1~X5)

측정 대상은 order DB(`order_inventory_*` 테이블)와 location DB(`inventory_*` 테이블)에 실제 마이그레이션(order `V1`~`V6`, location `V1`~`V4`)을 적용한 PostgreSQL 18.6 컨테이너 두 개(`max_prepared_transactions=64`, 컨테이너당 2 CPU·2 GB)다. 현행·2PC 모두 실제 저장소의 SQL(`INSERT ... ON CONFLICT`, `claim`, `UPDATE ... available_quantity >= ?`, 펜싱 `UPDATE`)을 그대로 복사해 쓰고 HTTP 호출만 같은 프로세스의 DB 왕복으로 대체했다(문서 방법 절의 "HTTP 제외").

### 가설별 결과 (영역 4 결과: 두 DB )

| # | 결과(수치, n) | 반증 발동 여부 | 해석 |
|---|---|---|---|
| X1 | 1 클라이언트·낮은 경합 p50: 현행 7.6/7.6/6.3 ms, 2PC 8.9/8.6/6.8 ms(각 n=3, run당 약 1,700 건, 중앙값 비 1.12). 8 클라이언트 낮음 0.98, 8 높음 1.20(오염 run 제외 1.40), 32 높음 1.09 ✓ | 발동(1.5배 미만, 1 클라이언트 칸 기준) ✓ | 지연 비용 반박. 가설은 "결정 로그 fsync가 비용"으로 쓰였는데 결정 로그 fsync 중앙값은 최대 0.31 ms(예비 run)였고, 2PC의 추가 비용은 `PREPARE`·`COMMIT PREPARED` 왕복이다. run 사이 편차가 방식 간 차이보다 커서 1.12는 방향만 보인다 |
| X2 | 코디네이터를 `Z4`(양쪽 prepared, 결정 전)·`Z5`(결정 로그에 C 기록 후, `COMMIT PREPARED` 전)에서 SIGKILL, 각 10회 합 n=20. 같은 SKU 행 경쟁 쓰기(location, 시도당 3개) n=60 중 55P03 60건(100%), 경과 최소 5.073 s·p50 5.176 s·최대 10.419 s. 같은 멱등 키 order 재시도 n=20 중 55P03 20건(100%), 경과 5.070~5.571 s. 대조: 다른 SKU 쓰기 n=20 전부 성공(p50 0.121 s), 무관한 order 삽입 n=20 전부 성공(p50 0.148 s) ✓ | 미발동 ✓ | 락 유지 서술이 맞다. 영향은 고아 트랜잭션이 잡은 SKU 행과 같은 멱등 키에 한정되고 다른 행은 영향이 없다. 5 s 초과분(최대 10.4 s)은 호스트 load1 30 대에서 나왔고 원인은 확정하지 못했다 |
| X3 | 같은 20회: `VACUUM (VERBOSE) inventory_location_stock`의 "dead but not yet removable"이 20/20회 모두 101행(최소=최대), 제거 0행. 고아가 없어진 뒤(sweep 해소 후) 같은 표에서 미제거 0행·제거 102행. `removable cutoff`가 고아의 prepared xid와 같은 회차 20/20, 새 REPEATABLE READ 세션의 `backend_xmin`이 그 xid와 같은 회차 20/20 ✓ | 미발동 ✓ | vacuum 정지 서술이 맞다. 고아 하나가 그 xid 이후 죽은 행 전부의 제거를 막는다(세션 하나가 아니라 prepared 트랜잭션이 기준점을 고정) |
| X4 | sweep 주기 5 s(grace 1 s), 코디네이터를 `Z4`·`Z5`·`Z6`에서 랜덤 위상으로 죽임 n=20(7/7/6), 모두 해소: 죽인 뒤 해소까지 최소 0.600 s·p50 3.447 s·p95 5.672 s·최대 5.933 s, 6 s 초과 0건, 5 s 초과 4건 ✓ | 미발동 ✓ | 주기+1 s 이하다. 한 번의 sweep 처리 시간은 최대 0.253 s(n=28 tick). 이 구간에도 같은 SKU 쓰기 n=40 중 3건이 `lock_timeout` 5 s에 걸려 55P03(전부 `Z4`) |
| X5 | location 컨테이너를 `docker pause`하고 order 쪽 락 보유를 측정. 2PC `prepare` 직전 정지 + 읽기 제한 시간 2 s: 장애 후 order 락 보유 2.011~2.040 s(n=5), 같은 키 재시도 1.86~1.91 s 대기 후 성공. 제한 시간 없음: 보유 6.357~7.190 s(n=5, 정지 6 s), 같은 키 재시도 5/5 55P03(5.11~5.27 s). 현행(`K2`·`K4` 정지, 읽기 제한 시간 2 s) 각 n=5: 락 보유 샘플 0.0 s, 같은 키 재시도 0.082~0.321 s에 성공 ✓ | 미발동 ✓ | 2PC는 대기 시간만큼 order 쪽 락을 보유하고 현행은 0 s다. 결정 후 order를 먼저 커밋하는 순서(`C2b`)는 order 락 보유를 0.006~0.039 s로 줄이지만 location 쪽 prepared가 참여자 복구까지 남는다(아래 표) |

### 성공 경로 지연·처리량 (칸 a)

잠금 구간 10:19:56~10:27:44(8분 보유 중 7분 48초, 만료 25 s 전 새 run 시작 중단), 15 s 측정(워밍업 1 s 제외)·run당 클라이언트 프로세스 1/8/32개, 라운드 로빈 3라운드. 지연은 예약 한 건 전체(ms), run별 값을 `/`로 나열했다. `오염 run`은 시작·끝 load1이 12를 넘은 run 수다(8 클라이언트 낮음·높음 라운드 1의 12.08~13.25, 외부 라벨 컨테이너는 없음). 정합 칸은 run 뒤 order·location 건수와 재고 불변식 대조 통과 run 수다.

| 클라이언트 | 경합 | 방식 | runs | n_ok(합) | 처리량 tx/s (run별) | p50 ms (run별) | p95 ms (run별) | p99 ms (run별) | 오류 | 정합 | 오염 run |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | low | current | 3 | 5345 | 110.9/105.4/140.1 | 7.6/7.6/6.3 | 16.9/18.2/14.2 | 22.6/23.8/23.4 | 0 | 3/3 | 0 |
| 1 | low | 2pc | 3 | 4984 | 90.8/99.0/142.5 | 8.9/8.6/6.8 | 23.4/18.1/9.0 | 33.9/24.2/12.9 | 0 | 3/3 | 0 |
| 1 | low | hybrid | 3 | 4068 | 64.3/74.9/132.1 | 13.5/10.2/7.4 | 29.6/27.8/10.9 | 46.9/38.9/16.1 | 0 | 3/3 | 0 |
| 8 | high | current | 3 | 16815 | 351.5/279.7/489.7 | 20.1/24.3/15.1 | 39.7/54.3/26.3 | 63.2/87.5/35.1 | 0 | 3/3 | 1 |
| 8 | high | 2pc | 3 | 13384 | 392.9/287.9/211.5 | 17.7/24.0/31.3 | 39.5/54.5/82.1 | 56.2/73.6/123.5 | 0 | 3/3 | 1 |
| 8 | low | current | 3 | 19546 | 287.2/376.5/639.3 | 23.9/20.2/11.8 | 52.6/31.6/17.5 | 102.1/42.3/21.3 | 0 | 3/3 | 1 |
| 8 | low | 2pc | 3 | 18989 | 351.1/352.4/562.4 | 19.9/21.4/13.5 | 38.2/32.6/19.0 | 76.1/44.9/22.9 | 0 | 3/3 | 1 |
| 32 | high | current | 3 | 11194 | 270.9/266.6/208.8 | 76.8/81.4/100.4 | 358.0/352.3/461.6 | 613.2/542.7/708.2 | 0 | 3/3 | 0 |
| 32 | high | 2pc | 2 | 7617 | 212.6/295.2 | 104.2/73.0 | 431.1/315.1 | 708.2/517.3 | 0 | 2/2 | 0 |

중앙값 비(run별 p50의 중앙값끼리, 오염 run 포함):

| 클라이언트 | 경합 | 2PC/현행 p50 중앙값 비 | hybrid/현행 p50 비 | 2PC/현행 처리량 비 |
|---|---|---|---|---|
| 1 | low | 1.12 | 1.34 | 0.89 |
| 8 | high | 1.20 | - | 0.82 |
| 8 | low | 0.98 | - | 0.94 |
| 32 | high | 1.09 | - | 0.95 |

- 오염 run(8 클라이언트 낮음 `current`·`2pc` 라운드 1, 높음 라운드 1)을 빼면 8 낮음 p50 중앙값은 현행 16.0 ms·2PC 17.5 ms(비 1.09, n=2씩), 8 높음은 현행 19.7 ms·2PC 27.7 ms(비 1.40, n=2씩)이다. 같은 방식·같은 칸의 run 사이 처리량 편차(예: 1 클라이언트 현행 105~140 tx/s)가 방식 간 차이보다 크다.
- 실행한 칸: 1 클라이언트 낮음(현행·2PC·하이브리드 각 3 run), 8 클라이언트 낮음·높음(현행·2PC 각 3 run), 32 클라이언트 높음(현행 3 run, 2PC 2 run: 라운드 3의 2PC는 잠금 만료 전 시작 중단). 실행하지 못한 칸: 32 클라이언트 낮음, 하이브리드의 8·32 클라이언트(잠금 구간 8분에 맞추려 제외), 1 클라이언트 높음(클라이언트가 하나라 낮음과 같은 칸이다).

### 코디네이터 사망 (칸 b)

| 항목 | 측정 | n |
|---|---|---|
| `pg_locks`(prepared, `pid IS NULL`) | location: `inventory_location_stock`·`inventory_reservation` 등 테이블과 인덱스에 `RowExclusiveLock` 보유, order: `order_inventory_location_command`·`order_inventory_event` 등 보유. 가상 트랜잭션 id는 고아마다 하나(`69/5`, `71/4` 형태) | 첫 2회 |
| 대기 중 잠금 | 1 s 시점 `pg_locks` 미부여 3건(경쟁 쓰기 3개), `wait_event` `Lock/tuple` 2·`Lock/transactionid` 1 | 20회 모두 3건 |
| 고아 상태 | 1 s 시점 `pg_prepared_xacts` 양쪽 각 1건, 나이 1.100 s | 첫 회 |
| 최종 상태 | `Z4`는 abort(양쪽 none, `pg_prepared_xacts` 0건), `Z5`는 commit(order·location 둘 다 RESERVED) | 10+10 |
| 고아 해소(sweep 즉시 실행) | 0.017~0.079 s | 20회 |

### 참여자 느림·죽음 (칸 c)

| 시나리오(n=5씩) | 결과 | order 락 보유(장애 후) | 같은 키 재시도 | 최종 상태 |
|---|---|---|---|---|
| 2PC, `prepare` 직전 정지, 제한 시간 2 s | abort(`ParticipantTimeout`) 5/5 | 2.011~2.040 s | 1.86~1.91 s 후 성공 | order·location 둘 다 none 5/5 |
| 2PC, 같은 정지, 제한 시간 없음(정지 6 s) | 정지 해제 후 commit 5/5 | 6.357~7.190 s | 55P03 5/5(5.11~5.27 s) | 둘 다 RESERVED 5/5 |
| 2PC, 결정(C) 후 정지, location 먼저 커밋 | 커밋 보류(location) 5/5, sweep이 이어서 커밋 | 2.028~2.070 s | 1.88~1.93 s 후 성공 | 둘 다 RESERVED 5/5 |
| 2PC, 결정(C) 후 정지, order 먼저 커밋 | 커밋 보류(location) 5/5 | 0.006~0.039 s | 0.088~0.305 s 성공 | 둘 다 RESERVED 5/5 |
| 2PC, 결정(C) 후 location `docker kill`·재시작 | 커밋 보류 5/5, prepared가 재시작 뒤에도 유지 5/5 | 0.018~0.041 s | 0.050~0.317 s 성공 | 둘 다 RESERVED 5/5 |
| 현행 `K2`(location 예약 호출 직전 정지, 제한 시간 2 s) | `RETRY_PENDING` 5/5, 복구 0.168~0.456 s | 0.0 s | 0.082~0.206 s 성공 | 둘 다 RESERVED 5/5 |
| 현행 `K4`(location 커밋 직후 정지) | 정상 `RESERVED` 5/5 | 0.0 s | 0.089~0.321 s 성공 | 둘 다 RESERVED 5/5 |

- `docker kill` 시나리오의 컨테이너 재시작 시간 0.288~1.527 s, 재시작 뒤 prepared 해소까지 3.599~8.248 s(정지 해제 기준), 재시작 직후 같은 SKU 쓰기 대기 0.360~4.711 s.
- 정지 해제 후 location prepared가 해소되기까지(prepared가 남은 세 시나리오 n=15, `kill` 제외) 0.519~2.700 s.

### 충돌 지점 정합성 (칸 d)

| 설계 | N | 죽인 횟수 | 지점 분포 | 원자성 위반(order RESERVED ⇔ location RESERVED 불일치) | 재고 불변식 위반(`initial_capacity = available + reserved + claimed`, 예약 합과 `reserved` 일치) | 최종 RESERVED | 복구가 필요했던 회차의 복구 시간 |
|---|---|---|---|---|---|---|
| 현행(복구 워커 모사, 임대 1 s) | 100 | 96 | `K0` 14·`K1` 13·`K2` 15·`K3` 15·`K4` 14·`K5` 16·`K6` 9·무사망 4 | 0 | 0 | 73(양쪽 일치), 27은 양쪽 none | n=60, p50 1.30 s·최대 2.13 s(임대 대기 포함) |
| 2PC(결정 로그+sweep, grace 1 s) | 100 | 89 | `Z0` 6·`Z1` 14·`Z2` 10·`Z3` 10·`Z4` 7·`Z5` 12·`Z6` 19·`Z7` 11·무사망 11 | 0 | 0 | 53(양쪽 일치), 47은 양쪽 none | n=58, p50 0.08 s·최대 1.38 s |

- 2PC에서 `Z4`(7회)는 sweep이 abort로 해소, `Z5`(12회)·`Z6`(19회)는 commit으로 해소했다. 사전 상태가 `P:o`(order만 prepared)인 39회, 양쪽 prepared인 19회였다.
- 현행에서 order 의도가 커밋되기 전에 죽은 `K0`·`K1`(27회)은 어느 쪽도 흔적이 없고(클라이언트가 같은 멱등 키로 재시도), `K2`~`K5`(60회)는 복구가 `RESERVED`로 마무리했다.

### 운영 복잡도 (칸 e)

| 항목 | 현행 | 2PC 시제품 |
|---|---|---|
| 추가 구성요소 | 없음(기존 `LocationReservationWorker` 폴링 1 s) | 결정 로그(내구 저장소 필요, 로그 유실은 해소 불가), 복구 sweep 프로세스, 두 DB의 `max_prepared_transactions`(재시작 필요, 동시 prepared 상한) |
| 설정 | 임대 30 s, 최대 시도 8, 연결 250 ms, 읽기 2 s, 배치 100 | 위 + sweep 주기, grace, `max_prepared_transactions`, 결정 로그 fsync 정책 |
| 장애 시 수동 단계 | 자동 복구, `NEEDS_REVIEW` 상태 처리만 코드에 존재 | 고아 발견(`pg_prepared_xacts`) → 결정 로그 조회 → `COMMIT`/`ROLLBACK PREPARED` → vacuum 기준점 확인(4단계, 시제품 기준) |
| 상태·보상 코드(실측 행 수) | `LocationReservationServiceImpl` 732행, 보상 메서드 5개 합 70행, 예약 경로 메서드 5개 합 138행, 상태 enum 7개+10개, 관련 토큰 28개 파일 148행(`COMPENSAT` 등), 위치 쪽 `V2` 마이그레이션 tombstone 6행 | 결정 로그 클래스 57행, prepare·finish 보조 25행, `sweep_once` 22행, sweep 프로세스 27행, 코디네이터 결정 17행, 합 148행(운영 하드닝 없는 최소 구현) |

`LocationReservation*`(order·application)와 location 예약 클래스 전체는 11개 파일 1,989행이다(명령: 파일별 `wc -l` 합).

### 한계

- HTTP 호출 없이 같은 프로세스의 DB 왕복으로 대체했고 현행 복구는 `recoverDue`의 SQL 순서를 모사했다(실제 Spring 코드를 호출하지 않았다). 지연 절대값은 HTTP 구간만큼 낮게 나온다.
- 호스트 load1이 정합성 칸(b·c·d) 동안 17~34였다(다른 에이전트의 벤치마크). 지연이 아닌 pass/fail·상태 칸이라 결과에 영향이 없지만, X2 경과 최대치(10.4 s)와 X4 상한(5.933 s)은 부하 아래 값이다.
- 결정 로그는 단일 파일 fsync(macOS `fsync`, `F_FULLFSYNC` 아님)이고 복제·장애 조치가 없다. 코디네이터 노드가 로그와 함께 사라지는 경우는 측정하지 않았다.
- 칸 d의 현행 복구 시간에는 임대 1 s 대기가 들어 있다(운영 기본 30 s).
- 칸 c의 `docker kill`은 location 컨테이너만 죽였다(order 컨테이너 사망·동시 사망은 측정하지 않았다).
- 칸 c의 `재고 불변식 위반` 5건(`snapshot_counts`)은 경쟁 쓰기 탐침이 예약 행 없이 `reserved_quantity`만 올린 것이 원인이며 시제품 결함이 아니다(칸 d·a 불변식 위반은 0).

### 주장

| 주장 | 증거 | 확신도 |
|---|---|---|
| 고아 prepared 트랜잭션은 같은 SKU·같은 멱등 키 쓰기를 `lock_timeout`까지 막는다 | X2: 55P03 60/60, 20/20 | 확인 |
| 고아 하나가 vacuum 제거 기준점을 자기 xid로 고정한다 | X3: 미제거 101행 20/20, cutoff=xid 20/20 | 확인 |
| sweep 주기 5 s에서 해소는 주기+1 s 이하다 | X4: 최대 5.933 s, n=20(load1 30 대에서) | 확인 |
| 참여자 정지 시 2PC만 order 락을 대기 시간만큼 쥔다 | X5: 2.0 s / 6.4~7.2 s 대 0.0 s | 확인 |
| 두 설계 모두 충돌 지점 100회씩에서 원자성·재고 불변식 위반이 없다 | 칸 d 위반 0/100, 0/100 | 확인 |
| 성공 경로 p50·처리량 수치 | 칸 a 표(잠금 아래, load는 표에 표기) | local-only(에이전트 보고) |
| 결정 로그 노드 유실 시 2PC가 해소 불가 | 구조상 추론, 측정하지 않음 | 재현 못 함(원인 미확정) |

### 결정

**현행 유지, 시제품 경로 미제작(코드 출하 없음, 되돌릴 것 없음).** 사전 등록 결정 규칙의 세 조건을 모두 만족해야 하는데 하나도 만족하지 못했다.

- (i) 하이브리드 성공 경로 p50 증가 20% 이하: 1 클라이언트 낮은 경합에서 하이브리드/현행 1.34(13.5/10.2/7.4 ms 대 7.6/7.6/6.3 ms, 각 n=3). 2PC 단독은 1.12지만 하이브리드는 order 의도 선커밋이 더해져 20%를 넘었다. 편차가 커서 이 칸은 확정하지 못하지만 1.34는 기준 밖이다.
- (ii) 코디네이터 사망 시 경쟁 쓰기 대기가 sweep 주기 이하: 주기 5 s에서 해소 최대 5.933 s, 5 s 초과 4/20회(부하 load1 30 대). 같은 구간 같은 SKU 쓰기 40건 중 3건은 55P03 실패. 미충족.
- (iii) 없앤 부분 실패 상태 코드가 sweep·결정 로그 추가 코드보다 큼: 보상 메서드 합 70행 대 시제품의 sweep·결정 로그·prepare 보조 합 148행(하드닝 없는 최소 구현). 하이브리드는 reserve 단계 밖의 outbox·TCC를 유지하므로 상태 enum(7개+10개)도 못 없앤다. 미충족.

현행은 참여자 정지 시 order 락 보유 0.0 s(2PC는 2.0 s, 제한 시간 없으면 6.4~7.2 s)이고 코디네이터 사망에 대응하는 고아 prepared가 없다. 2PC의 정합성은 충돌 지점 100회에서 현행과 같은 0 위반이므로 정합성은 결정 요인이 아니다.

**뒤집을 조건(측정 가능)**: 세 조건이 동시에 성립할 때만 시제품 경로를 만든다. (i) 깨끗한 창에서 하이브리드/현행 p50 비 ≤ 1.20(n≥10 run, 현재 1.34, n=3), (ii) 해소 지연 최대값이 sweep 주기 이하이고 경쟁 쓰기 대기가 `lock_timeout`보다 짧음(주기를 줄이거나 결정 로그 조회로 즉시 해소; 현재 최대 5.933 s 대 주기 5 s), (iii) 하이브리드 도입으로 제거되는 상태·보상 코드가 148행(시제품 최소 구현 크기, 운영 하드닝은 별도)을 넘음(현재 제거 가능 보상 메서드 70행). 외부 조건으로 로그 저장소를 복제 가능한 내구 저장소로 두는 비용도 별도로 들어간다.

### 삭제한 파일의 내용 (영역 4 결과: 두 DB )

저장소에 올리지 않은 하네스는 Python(psycopg 3) 스크립트(공통 라이브러리, 코디네이터, sweep, 칸별 드라이버, 측정 행렬 실행기)이며 호스트에서 컨테이너 두 개의 임의 포트로 접속한다.

- 공통 라이브러리: 접속 옵션 `statement_timeout=30000 idle_in_transaction_session_timeout=60000 lock_timeout=5000`. 비즈니스 SQL은 실제 저장소의 SQL을 복사: order `INSERT INTO order_inventory_reservation_workflow ... ON CONFLICT (idempotency_key) DO NOTHING`, 명령·항목 삽입, 헤더·위치 조회, `claim`(임대 토큰), 펜싱 `UPDATE ... WHERE lease_token = ?`; location `INSERT INTO inventory_reservation ... ON CONFLICT DO NOTHING`, 재사용 시 `SELECT ... FOR UPDATE`, `UPDATE inventory_location_stock SET available_quantity = available_quantity - ?, reserved_quantity = reserved_quantity + ? WHERE ... AND available_quantity >= ?`, 항목 삽입, 조회 2개. 용량 10,000,000, 낮은 경합은 SKU 1,000개 균등 선택, 높은 경합은 SKU 1개.
- 현행: order 의도(워크플로·명령·항목·`claim`) 커밋 → location 예약 커밋 → order 펜싱 완료 커밋(커밋 3번, 지점 `K0`~`K6`). 2PC: order 의도·완료 → `PREPARE TRANSACTION` → location 예약 → `PREPARE TRANSACTION` → 결정 로그 `C` 줄 append+flock+fsync → location `COMMIT PREPARED` → order `COMMIT PREPARED`(지점 `Z0`~`Z7`). 하이브리드: order 의도를 먼저 커밋한 뒤 같은 2PC.
- 결정 로그: 줄 `C <wf>`/`A <wf>`, flock 아래 꼬리를 읽은 뒤 첫 결정이 이긴다. sweep: 양쪽 `pg_prepared_xacts`(`gid LIKE 'qt2pc.%'`)를 모아 로그의 C면 commit, 아니고 나이가 grace(1 s) 이상이면 A를 기록하고 rollback.
- 칸 a: 클라이언트별 프로세스(fork) 1/8/32개, 시작 동기화, 워밍업 1 s 제외 15 s 측정, 지연은 한 번의 예약 전체(order 의도부터 마지막 커밋)를 `perf_counter`로 잰 ms, 백분위는 정렬 후 `round(p/100 × (n-1))` 번째, 처리량은 측정 구간 성공 수 ÷ 15 s. 매 run 전 `TRUNCATE`·재고 재삽입·`ANALYZE`·`CHECKPOINT`, run 뒤 order 워크플로·명령, location 예약 수와 재고 불변식을 대조(정합 칸). 라운드 로빈(현행·2PC·하이브리드를 번갈아) 순서로 3라운드, 잠금 만료 25 s 전에 새 run 시작 중단. 오염 판정: 시작·끝 load1 > 12 또는 `queuetx` 아닌 라벨 컨테이너 존재.
- 칸 b: 코디네이터를 별도 프로세스로 띄워 지점에서 `SIGKILL`(표식 파일 fsync), 이후 쓰기 5개를 동시에 시작(location 같은 SKU `UPDATE` ×3, 다른 SKU 1, order 같은 멱등 키 삽입 1, 무관한 order 삽입 1). 1 s 뒤 `pg_locks`, `pg_stat_activity`, `pg_prepared_xacts`, 죽은 행 100개 생성 후 `VACUUM (VERBOSE)`(autovacuum 끔), REPEATABLE READ 세션의 `backend_xmin`, sweep 후 다시 `VACUUM`. 칸 b의 해소 시간은 별도 sweep 프로세스(주기 5 s, grace 1 s)가 돌 때 `pg_prepared_xacts` 20 ms 간격 폴링으로 측정.
- 칸 c: 코디네이터 프로세스가 지점(`Z2`, `Z5`, `K2`, `K4`)에서 `docker pause qt-2pc-location`(또는 `docker kill`)을 실행, 6 s 뒤 `docker unpause`(또는 `start`). 별도 스레드가 0.1 s마다 order `pg_prepared_xacts`와 0.3 s 넘게 idle in transaction인 세션을 표본, 같은 키 재시도와 무관한 order 삽입을 반복. 현행도 같은 읽기 제한 시간 2 s.
- 칸 d: 지점을 무작위(`none` 포함)로 골라 코디네이터를 죽이고 복구(2PC: sweep을 0.25 s마다, 현행: 임대 1 s 만료 후 `recoverDue` 모사)한 뒤 order 상태·command 상태·location 예약 상태·남은 prepared를 분류하고, 마지막에 재고 불변식 질의를 실행. 시드 21(2PC)·22(현행).
- 코드 규모: 파일별 `wc -l`과 메서드 시작 줄 간격, `git grep -c -E 'COMPENSAT|RELEASE_PENDING|RETRY_PENDING|CANCEL_PENDING|NEEDS_REVIEW|tombstone|Tombstone'` (order·application·inventory-location main).

### 삭제한 파일의 내용 (영역 1·2)

구동 스크립트와 원자료(JSON, 로그)는 저장소에 올리지 않았다(실험 산출물 규칙). 이력에서 복원할 파일이 아니라 아래 설명으로 재현한다.

**영역 1 큐 하네스(Python, psycopg3 스레드).** run 하나는 PostgreSQL 18.6 컨테이너 하나(CPU 2, 메모리 2 GB, `shared_preload_libraries=pg_stat_statements`, `log_autovacuum_min_duration=0`)에 V1~V6을 적용한 DB다. 준비 단계에서 FK 3개(`payment_recovery_job`의 시도·환불 FK, 지급 outbox의 `payout_id` FK)를 하네스 한정으로 떼고, `payment_attempt` 50만 행과 그에 대응하는 완료된 `payment_recovery_job` 50만 행, 완료된 지급 outbox 10만 행을 삽입하고 `VACUUM (ANALYZE)` 후 `pg_stat_reset()`, `pg_stat_statements_reset()`, `CHECKPOINT`한다. 부하 프로세스 6개가 동시에 돌았다: (1) 결제 복구 생애, 도착 포아송 60/s: `enqueueAttempt`(INSERT … ON CONFLICT DO NOTHING) → 50~300 ms 뒤 `markAttemptForApplication` → 20 ms 뒤 `markAttemptApplied`, 3%는 지연 2초 뒤 worker 경로로 간다. (2) 결제 복구 worker 2개: 1초마다 `expireExhausted`, `resumeExpiredApprovalWaits(10)`, `claimNextDue(1)`을 최대 10번(일감이 없으면 중단). 청구한 일감은 200 ms 처리 뒤 80%는 `markForApplication` + `complete`, 나머지는 `reschedule`. (3) workflow: 도착 30/s INSERT, worker 1초마다 `claimNextDue`를 일감이 없을 때까지 최대 100번, 10 ms 처리 뒤 90%는 `RESERVED` → `CLAIMED`, 10%는 `RETRY_PENDING`. (4) webhook: 도착 5/s, worker 1초마다 reaper와 `claimDue`를 최대 10번, 30%는 실패 재예약, 70%는 완료(DELETE). (5) 지급: 도착 2/s, worker 5초마다 `claim(10)`, 증거 기록 뒤 60%는 `WAITING_PROVIDER`, 40%는 `COMPLETED`. (6) 이벤트 릴레이 2개: 0.2초마다 `claimLease` → 이벤트 조회 → `advanceFenced` 또는 `releaseLease`. 쓴 SQL은 저장소의 문장(`PaymentRecoveryRepository`, `LocationReservationRepository`, `PayPalWebhookRetryRepository`, `JdbcPayoutDispatchStore`, `ReservationEventRepository`)을 그대로 옮기고 클라이언트 시각 인자만 서버 시각으로 바꿨다. 호출 지연은 호출 하나를 `time.perf_counter()`로 쟀다. 스냅샷은 워밍업 끝과 run 끝에 `pg_stat_user_tables`(`n_tup_ins/upd/hot_upd/del`, `n_live_tup`, `n_dead_tup`, `autovacuum_count`, 크기), `pg_stat_wal`(`wal_bytes`, `wal_fpi`), 15초마다 죽은 튜플 시계열, 끝에 `pg_stat_statements` 상위 40개. A5는 준비 단계에서 인덱스 2개를 추가한다. 쌍 run은 A0 컨테이너 2개와 A5 컨테이너 2개를 같은 호스트 잠금 보유 중 동시에 시작했다(앞선 동시 3 run이 끝난 컨테이너를 다시 시작해 사용했으므로 이력이 약 57.5만 행으로 자랐다).

**Q2 시험.** 새 컨테이너에 V1~V6, `payment_recovery_job`의 시도 FK 제거, `fillfactor` 100 또는 75 설정, 행 1,500개(`PENDING`, `available_at` 과거) 삽입, `VACUUM`, `pg_stat_reset_single_table_counters`, `claimNextDue(1)` SQL을 일감이 없을 때까지 반복(1,500회), 1.5초 뒤 `n_tup_upd`, `n_tup_hot_upd` 조회. 설정마다 3회.

**유휴 앱 시험.** 빌드한 앱 JAR을 JDK 25(`-Xms512m -Xmx1g`)로 호스트에서 실행해 컨테이너 PostgreSQL에 연결(`max_connections=200`, `statement_timeout=30000`, `lock_timeout=5000`, `idle_in_transaction_session_timeout=60000`), 결제 복구 워커·이벤트 릴레이·지급 워커를 켜고(`atlas.payment.paypal.payouts.enabled`, `…worker.enabled`, 암호화 키와 PayPal 자격 증명은 형식만 맞춘 더미), 기동 뒤 25초 지나 `pg_stat_statements_reset()` 후 60초 유휴. 시드가 있는 칸은 앱을 한 번 띄워 Flyway를 적용하고 멈춘 뒤 `payment_attempt`와 완료된 복구 일감 50만, 완료된 지급 25만, 완료된 workflow 10만을 삽입하고 `VACUUM ANALYZE`, 다시 기동.

**지급 claim 계획 확대.** 같은 컨테이너에 완료된 지급 outbox를 20만, 100만 행까지 채우고 `ix_settlement_payout_dispatch_leased`를 만들기 전후의 `EXPLAIN (ANALYZE, BUFFERS)`를 읽었다(20만 행 7,402 → 10 블록, 100만 행 36,030 → 11 블록, 인덱스 8 KB).

**영역 2.** 컨테이너에 V1~V6을 적용하고 한 프로세스에서 생산자 스레드(도착 5/s 포아송, 삽입 뒤 삽입 시각을 기록)와 워커 스레드(폴링: 주기마다 청구를 비울 때까지 반복, 주기 1 s 또는 0.2 s)를 돌려 청구 시각에서 삽입 시각을 뺐다. NOTIFY 변형은 삽입과 `pg_notify('wf_due','')`를 한 트랜잭션에 넣고 리스너 연결이 `notifies(timeout=30)`에서 깨어 청구한다. W4는 `pgbench -n -c {1,8,32} -T 5 -f`로 `BEGIN; INSERT INTO wk(p) VALUES ('x'); END;`와 같은 문장에 `SELECT pg_notify('wk','')`를 더한 스크립트를 3회씩 돌렸고, `LISTEN wk`를 건 psql 연결 하나가 0.2초마다 질의를 보내 알림을 비웠다.

### 실행 기록과 편차

- 사전 등록 이후 추가: Q9~Q11(큐 부하 측정 전 커밋), 팔 A5. 사전 등록 규칙에서 벗어난 것: Q11 발동에도 인덱스를 출하(위 결정과 ADR-0101).
- 실행하지 못한 칸: A1~A4(영역 1). 호스트 잠금을 다른 에이전트의 측정·전체 검사와 번갈아 써야 했고, 동시 3 run 두 묶음이 호스트 load1 29~33에서 오염돼 쌍 run이 유일한 깨끗한 창이었다.
- 영역 2 종단 간 NOTIFY 칸과 유휴 칸은 하네스 결함으로 무효(위).
- 마이그레이션 번호: order DB `V8`만 사용(V7은 다른 PR 소유). location DB `V5`는 쓰지 않았다.
