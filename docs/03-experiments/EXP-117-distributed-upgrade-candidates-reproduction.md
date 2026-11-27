# EXP-117: 분산 배포 전제에서 남은 업그레이드 후보(F1·F2·F3·G1·C4·E3)를 재현으로 판정한다

- 상태: 측정 전 사전 등록(이 절까지). 결과는 아래 "결과" 절에 측정 뒤 덧붙인다.
- 날짜: 2026-11-27
- 관련: [#729](https://github.com/Neo1228/ATLAS/issues/729), [ADR-0038](../02-decisions/adr/ADR-0038-durable-payment-recovery-workflow.md), [ADR-0047](../02-decisions/adr/ADR-0047-abandoned-approval-deadline.md), [ADR-0067](../02-decisions/adr/ADR-0067-event-log-kafka-relay.md), [ADR-0072](../02-decisions/adr/ADR-0072-infrastructure-inventory-and-assumptions.md), [ADR-0077](../02-decisions/adr/ADR-0077-keep-reorder-idempotency-records.md), [EXP-064](EXP-064-location-fault-convergence.md), [EXP-083](EXP-083-reorder-idempotency-retention.md), [EXP-086](EXP-086-admission-k-ttl-and-authorize-window.md)

## 배포 전제

단일 서버가 아니라 다음을 전제로 판단한다. 후보마다 결과가 어느 전제에 기대는지 따로 적는다.

| 기호 | 전제 |
|---|---|
| P-N | load balancer 뒤 app 인스턴스 여러 개가 같은 PostgreSQL을 쓴다 |
| P-K | rolling 배포와 프로세스 강제 종료(`kill -9`, OOM)가 처리 중 작업을 버린다 |
| P-F | DB primary failover와 replica가 있다(스트리밍 복제) |
| P-L | app, location DB, PayPal 사이에 지연과 분할이 생긴다 |

## 후보와 문제 한 문장 (코드 읽기, 측정 아님)

| 후보 | 문제 한 문장 | 기대는 전제 |
|---|---|---|
| F1 | 결제 복구 기본값(`max-attempts 8`, `base-backoff PT1S`, `max-backoff PT15M`, `max-age PT24H`)에서 backoff가 시도마다 두 배라 8회 소진까지 약 1~2분이고, ADR-0038이 적은 "15분 상한·24시간 창"에 닿기 전에 PayPal 장애·분할이 2분만 넘어도 미확정 결제가 모두 `NEEDS_REVIEW`(수동)로 간다 | P-L, P-K |
| F2 | 비동기 복제 failover로 결제 시도 커밋이 사라지면, 그 커밋 뒤에 나간 PayPal 호출의 결과는 ATLAS DB에 흔적이 없고 PayPal 쪽 기록과 대조해야만 보인다 | P-F |
| F3 | reorder 멱등 기록을 영구 보존하는 ADR-0077 결정은 100만 행 이하에서만 측정됐다 | 없음(공유 DB 키라 인스턴스 수와 무관) |
| G1 | lease가 만료된 coordinator의 location 쓰기가 늦게 도착하면 새 coordinator의 결정과 어긋날 수 있다(fencing token 부재) | P-N, P-L, P-K |
| C4 | 비동기 복제에서 primary가 확인한 커밋이 failover 뒤 사라질 수 있다 | P-F |
| E3 | event relay가 `pg_snapshot_xmin` horizon 아래만 읽으므로 xid를 쥔 긴 트랜잭션이 그 시간만큼 이벤트 전달을 멈춘다. CDC(논리 복제)는 커밋 순서로 읽어 이 정지가 없다 | P-N(다른 인스턴스의 트랜잭션도 horizon을 잡음) |

## 가설과 반증 조건 (측정 전에 적음)

| 번호 | 가설 | 반증 조건 | 방법 |
|---|---|---|---|
| H1 | 기본값에서 provider 조회가 매번 실패하면 `PAYMENT_ATTEMPT` job은 8번째 claim 뒤 `NEEDS_REVIEW`(`MAX_ATTEMPTS`)가 되고, 그 8회 동안 DB에 기록된 재예약 지연(`available_at - updated_at`) 합은 300초 미만이다 | 8번째 claim 뒤 `PENDING`이거나 지연 합 ≥ 300초 | 실제 worker + PayPal stub 503 + PostgreSQL 통합 시험. 시간은 `available_at`을 당겨 건너뛰고 기록된 지연 값을 합한다 |
| H2 | 처리 중 강제 종료로 lease가 만료된 claim도 `attempt_count`를 쓴다. 같은 job을 잡고 8번 죽으면 `expireExhausted`가 `NEEDS_REVIEW`로 만든다. 그래서 `max-attempts`만 키우는 수정은 독이 든 job(poison job)이 인스턴스를 죽이는 횟수도 같이 키운다 | 현재 코드에서 버려진 claim 8회 뒤 `NEEDS_REVIEW`가 아님 | 같은 시험에서 claim만 하고 결과를 쓰지 않은 채 lease를 만료시킨다 |
| H3 | 버려진 claim을 따로 세는 열(V12)과, 결과를 기록한 재시도에는 창(`max-age`)이 먼저 묶이도록 올린 `max-attempts` 기본값으로 (a) H1 시나리오의 job은 창 전에는 `NEEDS_REVIEW`가 되지 않고 provider 회복 뒤 다음 claim에서 `COMPLETED`가 되며 (b) H2 시나리오는 여전히 버려진 claim 8회 뒤 `NEEDS_REVIEW`가 된다 | (a)나 (b) 중 하나라도 어긋남 | 같은 통합 시험, 수정 전 코드에서 실패·수정 뒤 통과, 변이 확인 |
| H4 | 비동기 스트리밍 복제(`synchronous_standby_names` 비어 있음)에서 primary가 확인한 커밋 중 일부가 primary 강제 종료 → standby 승격 뒤 사라진다. standby가 끊긴 동안 확인된 커밋 N건은 N건 모두 사라진다 | 끊긴 경우 손실 < N, 또는 부하 중 종료 5회 모두 손실 0이고 끊긴 경우도 손실 0 | PostgreSQL 18 컨테이너 2개(primary, standby), 확인된 커밋 번호를 클라이언트가 기록하고 승격 뒤 대조 |
| H5 | `synchronous_standby_names = '*'`(`synchronous_commit = on`)에서 같은 시나리오 손실은 0이고, 대신 standby가 끊기면 커밋 확인이 멈춘다(10초 안 확인 0건). 커밋 지연 비율(sync/async)은 같은 세션 3회 이상으로 잰다 | sync에서 손실 ≥ 1, 또는 standby 단절 중 커밋이 확인됨 | 같은 컨테이너 구성, 측정 잠금 아래 `pgbench` |
| H6 | 결제 시도와 복구 job은 PayPal 호출 전에 커밋된다(ADR-0038). 그래서 H4의 손실 구간에 든 시도는 PayPal에는 있고 ATLAS에는 없다. 기존 잔액 보고서 대사는 그 거래 행을 `UNMATCHED`로 분류한다 | 코드가 PayPal 호출 뒤에 커밋함, 또는 대사가 ATLAS에 없는 거래를 `UNMATCHED`로 분류하지 않음 | 코드 읽기 + 기존 대사 시험 재실행 |
| H7 | lease 만료 뒤 늦게 도착하는 location 쓰기(reserve·claim·cancel·release·restore)는 현재 상태 기계와 취소 tombstone이 거절하거나 같은 결과로 수렴한다. coordinator 둘(같은 DB, 각자 lease)과 lease보다 긴 location 지연에서 주문·location 불일치 0건 | 불일치 ≥ 1건 | PostgreSQL 통합 시험(늦은 도착을 붙잡았다 푸는 방식) |
| H8 | xid를 쥔 트랜잭션이 T초 열려 있으면 그 사이 커밋된 이벤트는 relay 읽기에서 약 T초(±1초) 늦게 보인다. app 세션은 `statement_timeout 30초`·`idle_in_transaction_session_timeout 60초`로 묶인다 | 지연이 T와 무관 | PostgreSQL 컨테이너, relay 읽기 질의를 그대로 실행 |
| H9 | reorder 멱등 기록 1,000만 행에서 행당 크기는 100만 행 측정값(typical 약 310 B, EXP-083)의 ±15% 안이고, 키 조회 버퍼는 5 이하다 | 벗어남 | PostgreSQL 컨테이너, 합성 데이터, `EXPLAIN (ANALYZE, BUFFERS)` |

## 판정 규칙 (측정 전에 적음)

- 채택: 가설이 문제를 재현하고(H1·H2·H4·H7 중 해당), 수정의 효과를 같은 방법으로 측정했을 때(H3·H5)만 코드·시험·변이·되돌리기·운영 가이드와 함께 채택한다.
- 기각: 재현되지 않거나 수정 효과를 우리 쪽에서 잴 수 없으면 증거와 수치 재검토 조건을 적고 기각한다.
- C4는 가용성(쓰기 정지)과 RPO의 교환이라 재현·측정되더라도 배포 구성 결정은 사용자에게 근거와 선택지로 묻는다. app 코드가 그 결정에 기대는 부분만 이 실험에서 정한다.
- 시간 값(H5 지연 비율)은 측정 잠금 아래에서만 재고, 결론은 건수와 불변식으로 낸다.

## 결과

측정은 사전 등록 커밋(`docs(experiments): EXP-117 가설과 반증 조건을 측정 전에 등록한다`, 2026-11-27 23:46) 뒤에 했다.

### F1: 결제 복구 재시도 예산 (H1·H2·H3)

방법: `PaymentRecoveryRetryBudgetIntegrationTest`. PostgreSQL 18.6(Testcontainers), 실제 `PaymentRecoveryWorker`, PayPal stub(`create-order`가 503). 설정은 main `application.yml`을 파일로 읽어 자리표시자 기본값으로 푼 운영 기본값이다(시험 classpath의 `application.yml`이 main 파일을 가리기 때문). 시간은 기다리지 않는다. worker가 기록한 재예약 지연(`available_at - CURRENT_TIMESTAMP`)만큼 그 job의 `available_at`·`retry_window_started_at`·`first_enqueued_at`을 과거로 옮겨 가상 시계를 만들고, 창 판정은 운영 SQL이 DB 시각으로 한다. 가상 시각은 기록된 지연의 합이고, 운영에서는 여기에 initial delay 30초가 더해진다. 강제 종료는 claim만 하고 결과를 쓰지 않은 채 `lease_until`을 과거로 옮겨 흉내 냈다.

수정 전(`origin/main` 57e6ccd7에 시험 사본만 넣은 별도 worktree, 5회):

| 회차 | H1 claim 수 | H1 가상 시각(초) | H1 최종 | H2 버려진 claim 수 | H2 최종 |
|---|---|---|---|---|---|
| 1 | 8 | 83.1 | `NEEDS_REVIEW`(`PROVIDER_RESULT_UNRESOLVED`) | 8 | `NEEDS_REVIEW`(`MAX_ATTEMPTS`) |
| 2 | 8 | 116.1 | 같음 | 8 | 같음 |
| 3 | 8 | 101.0 | 같음 | 8 | 같음 |
| 4 | 8 | 97.0 | 같음 | 8 | 같음 |
| 5 | 8 | 99.0 | 같음 | 8 | 같음 |

- H1 지지: 8번째 claim에서 `NEEDS_REVIEW`, 지연 합 83.1~116.1초(< 300초). 지연은 `[지수값/2, 지수값)` 균등이라 이론 범위는 63.5~127초다. `max-backoff PT15M`(지수값이 15분에 닿는 것은 11번째 시도)과 `max-age PT24H`는 기본 구성에서 판정에 한 번도 쓰이지 않았다.
- H2 지지: 버려진 claim도 `attempt_count`를 써서 8번째에서 멈췄다. 이 보호는 `max-attempts`에 묶여 있어 한도만 올리면 같이 사라진다.

수정(V12 `abandoned_claim_count`, `max-attempts` 202, `max-abandoned-claims` 8):

| 시험 | 결과 |
|---|---|
| 6시간 장애 뒤 회복 | 6시간 동안 `PENDING`(2회 실행: claim 42·41회, 가상 21,615·22,332초), 회복 뒤 다음 poll에서 같은 Request-Id로 주문 생성 성공, 결제 `AUTHORIZATION_PENDING`, job `WAITING_FOR_BUYER` |
| 3일 장애 | 창에서 `NEEDS_REVIEW`(`RECOVERY_WINDOW_EXPIRED`), 2회 실행: claim 133·140회 < 202, 가상 86,475·86,526초 ≥ 86,400초 |
| claim마다 강제 종료 | 버려진 claim 8회에서 `NEEDS_REVIEW`(`ABANDONED_CLAIMS`), PayPal 호출은 처음 제출 1회뿐. 운영자 requeue 뒤 두 계수 0 |
| 기록된 재시도 1시간 뒤 강제 종료 1회 | `PENDING` 유지, 다음 claim에서 `abandoned_claim_count = 1` |

- H3 지지((a)·(b) 모두).
- 202의 유도: 재예약 지연은 지수값(1초부터 두 배, 15분 상한)의 절반 이상이다. 10번째 시도까지 최솟값 합은 511.5초이고, 11번째부터는 지수값이 15분 상한에 닿아 최솟값이 450초씩이다. 시도 M회는 재예약 M−1번이므로 최솟값 합은 511.5 + (M−11) × 450초이고, 이것이 창 86,400초 이상이 되는 가장 작은 M이 202다(86,461.5초, 201회면 86,011.5초). 즉 창과 backoff 상한이 정한 값이고, 그 관계를 `PaymentRecoveryWorkerTest.shippedMaxAttemptsIsTheSmallestCountWhoseMinimumBackoffReachesTheWindow`가 main `application.yml` 기본값으로 확인한다(창이나 backoff 기본값이 바뀌면 실패한다).
- 두 방향의 시험: 몇 분짜리 장애가 더는 `NEEDS_REVIEW`로 가지 않음(6시간 장애 시험, 현재 main 값 8로 되돌리면 8번째 claim에서 실패: 변이 ①), 독이 든 job은 버려진 claim 8회에서 멈춤(한도만 올린 구성은 변이 ②로 잡힘).
- 버려진 claim 수가 기대는 배포 전제: lease(60초) 안에 결과를 쓰지 못한 claim을 센다. 원인은 강제 종료(P-K)만이 아니라 60초를 넘는 GC 정지나 DB와의 분할(P-N, P-L)도 된다. PayPal 호출은 연결 2초·읽기 10초 시간 제한이다(`atlas.payment.paypal.connect-timeout`·`read-timeout`, `PayPalRestClient`). 한 job이 조회·authorize·capture를 이어 불러도 호출 4번이 모두 시간 제한까지 걸리는 경우 약 48초라 lease 60초 안이다(코드 읽기, 측정 아님). 살아 있지만 느린 worker를 시험으로 쟀다.

| 경우 | 결과 |
|---|---|
| A가 lease를 잃고, B가 다시 잡아 503으로 재예약한 뒤 A가 결과를 씀 | 버려진 claim 1(한 번만 셈), `attempt_count` 2, A의 재예약·완료는 둘 다 0행(lease 토큰이 다름), `available_at`·`last_error_code`는 B의 값 그대로 |
| A가 lease를 잃었지만 아무도 다시 잡기 전에 결과를 씀 | 재예약 성공, 버려진 claim 0 |

- 변이 ⑥(재예약의 lease 토큰 조건 제거): 시험 6건 중 첫 경우 1건이 실패해 잡혔다. 한계: 같은 job에 60초 넘는 정지나 분할이 8번 겹치면 독이 든 job이 아니어도 `ABANDONED_CLAIMS`로 멈춘다. 그때도 결과는 덮이지 않고 운영자 requeue가 같은 요청 ID로 다시 묻는다. 7번째 버려진 claim 뒤 느린 worker가 아직 PayPal을 기다리는 동안 sweep이 job을 넘기면, 그 worker의 결과 기록은 막히고 다음 requeue에서 provider 조회로 수렴한다(실행하지 않음, SQL 읽기).
- 변이 확인(각 변이를 넣고 이 시험 클래스 4건을 돌림): ① `max-attempts` 기본값을 8로(되돌리기 설정) → 3건 실패, ② claim에서 버려진 claim 증가 제거 → 2건 실패, ③ sweep이 지금 만료된 lease를 세지 않음 → 1건 실패, ④ claim마다 무조건 증가 → 4건 실패, ⑤ requeue가 버려진 claim 수를 0으로 돌리지 않음 → 1건 실패. 다섯 변이 모두 잡혔다(⑥은 아래 느린 worker 시험).
- 관련 시험(`com.atlas.payment.*`, `PaymentRecovery*`, `com.atlas.schema.*`, `RemoteReservationCheckoutIntegrationTest`): 통과.
- 배포 전제: P-L(PayPal 장애·분할이 2분을 넘는 경우), P-K(강제 종료가 claim을 버리는 경우). 인스턴스 수(P-N)는 결과를 바꾸지 않는다. claim은 `available_at`과 `SKIP LOCKED`를 따르므로 인스턴스가 늘어도 job당 시도 간격은 같다.
- 한계: 가상 시계는 job 행의 시각만 옮긴다. 실제 24시간 동안의 PayPal 응답, 인스턴스 재시작 빈도는 재지 않았다. 강제 종료는 lease 만료로 흉내 냈고 실제 `kill -9`는 하지 않았다(DB가 보는 상태는 같다: lease 토큰이 남은 채 만료).

### F2: 3자 대사 (H6)

코드 읽기(실행 아님). `PaymentServiceImpl.completeApprovalForOwner`는 단계 시작(요청 ID 확정, 복구 job 활성화)을 한 트랜잭션으로 커밋한 뒤(170~188행) PayPal을 부르고(192행) 결과를 다른 트랜잭션으로 쓴다. 요청 ID는 결제 시도와 단계에서 정해진다(`phaseRequestId`).

- 비동기 복제 failover는 WAL 꼬리(커밋 접미사)를 잃는다. 결과 기록만 잃으면 남은 단계 시작 행과 복구 job이 같은 요청 ID로 다시 묻고 PayPal은 재생한다. 단계 시작까지 잃으면 행은 `AUTHORIZATION_PENDING`(provider 주문 ID 있음)으로 돌아가고 승인 deadline 뒤 재개가 provider 상태를 먼저 조회한다(ADR-0047). 주문 생성 결과까지 잃으면 ATLAS는 그 provider 주문을 모르지만, 돈을 움직이는 authorize·capture는 ATLAS가 불러야 일어나므로 그 주문에는 돈이 움직이지 않는다(구매자 승인만으로는 authorization이 생기지 않는다. EXP-086).
- 그래서 H6의 "PayPal에는 있고 ATLAS에는 없다"는 돈이 움직이지 않은 주문에서만 성립한다. 돈이 움직인 거래는 모두 앞선 커밋이 provider 참조를 쥐고 있어 기존 복구가 수렴한다. 남는 틈(예: 버그, 수동 조작)은 기존 잔액 보고서 대사가 `UNMATCHED`(`PROVIDER_CAPTURE_ID_NOT_FOUND`)로 분류한다(`PayPalBalanceReportReconciliationServiceTest.matchesCaptureAndRefundByExactProviderIdsAndTransactionFacts`. 이 브랜치에서 클래스 13건 통과).
- H6 부분 반증(돈이 움직인 경로). F2의 자동 일 단위 대사(Transaction Search)는 채택하지 않는다. 실제 판매자 보고서 접근이 Live gate LG-1에 묶여 있어(D-170) 우리 쪽에서 효과를 잴 수 없다.
- 배포 전제: P-F.

### G1: lease 만료 뒤 늦게 도착하는 location 쓰기 (H7) — #730으로 넘김

방법: PostgreSQL 통합 시험(이 PR에 넣지 않음). coordinator 둘을 서로 다른 `LocationReservationServiceImpl` 인스턴스로 두고 주문 DB와 시험용 시계를 같이 쓴다. location 호출은 실제 `HttpLocationClient`로 MockWebServer에 가고, location은 `InventoryReservationService` 규칙(멱등 키, 취소 tombstone, 상태 전이)을 따르는 메모리 모델이다. 늦은 도착은 location 적용 전(전달 지연) 또는 적용 뒤(응답 지연)에 붙잡았다 푸는 방식이다. "lease보다 긴 정지"는 A가 붙잡힌 동안 공유 시계를 31초 넘겨 흉내 냈다. 설정은 운영 기본값(lease 30초, location TTL 2분). location 쪽 한 경우는 실제 `InventoryReservationService`(PostgreSQL)로도 확인했다.

| 경우 | 결과(주문 / location / 재고) | 불일치 |
|---|---|---|
| 회수한 B가 예약한 뒤 A의 reserve 도착 | 같은 예약 재생 | 0 |
| B 취소 뒤 A의 reserve 도착(A 포기·정지 두 경우) | 409 `RESERVATION_CANCELLED`(tombstone) | 0 |
| **재고 부족으로 `FAILED`가 된 뒤 A의 reserve 도착** | 주문 `FAILED`, location **`RESERVED` 1개**(고아) | **1**(location TTL 2분 뒤 0) |
| B 취소 뒤 A의 claim 도착(포기·정지·TTL 만료 세 경우) | 409 `INVALID_STATE` | 0 |
| **A의 claim이 적용되고 응답이 lease보다 늦게 오는 동안 B가 취소** | 주문 `CANCELLED`, location `RELEASED` 둘(재고 일치). 그런데 **A의 `claim()`은 `CLAIMED`를 돌려줌** | 0(재고) / 결과 보고 1 |
| A의 취소 응답 지연 중 B claim, claim·취소 둘 다 늦게 도착, restock·restore 교차(4경우) | 수렴 또는 `NEEDS_REVIEW` 표시 | 0 |

- H7 반증(불일치 ≥ 1). 12경우 중 2경우.
- 고아 예약: location은 재고 부족으로 거절한 예약 행을 롤백해 그 키에 기록이 없고(`InventoryReservationService` 89~114행), 주문 쪽 보상은 `FAILED` location에 취소(tombstone)를 보내지 않는다. 늦은 reserve가 새 예약을 만든다. 인스턴스 둘이 필요 없고 한 coordinator의 재시도로도 생긴다(P-L). 영향은 TTL(2분) 동안 재고 1개 묶임이다.
- 오래된 claim 보고: `claim()`이 마지막 location 호출 뒤 lease로 막힌 쓰기 결과를 보지 않고 `CLAIMED`를 돌려준다. 코드 읽기로는 checkout이 이 값을 보고 capture할 수 있고, 그러면 재고는 풀렸는데 결제는 잡힌다. HTTP 지연만으로는 생기지 않는다(읽기 시간 제한이 lease 절반보다 짧게 시작 시 검사된다). 약 13초 넘는 프로세스 정지(GC, VM 정지)나 인스턴스 사이 시계 차가 필요하다(P-N, P-K).
- 이 두 결과와 제안 수정(주문 쪽에서 lease로 막힌 완료를 `CLAIMED`로 보고하지 않음. 줄인 시험 3건이 수정 전 1건 실패, 수정 뒤 두 번 0건 실패, 변이에서 1건 실패)은 lease·claim·fencing 변경을 맡은 [#730](https://github.com/Neo1228/ATLAS/issues/730)으로 넘겼다. 이 PR은 G1 코드와 마이그레이션을 바꾸지 않는다. location 쪽 fencing token(G1 원안)은 위 두 경우 어느 쪽도 막지 못한다. 고아 예약은 거절된 키에 대한 기록이 없어 비교할 토큰이 없고, 오래된 claim은 location이 claim을 적용한 시점에 A가 최신 소유자였다.
