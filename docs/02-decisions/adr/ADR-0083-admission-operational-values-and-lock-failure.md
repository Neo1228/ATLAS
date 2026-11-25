# ADR-0083: Admission의 K·ticket TTL 기본값, ticket payload·bot 방어·tombstone, 잠금 실패 규칙을 가정을 적고 정한다

- 상태: 채택(제안 PR, 병합은 통합 담당). 값은 구매자 행동 **가정**에 기대며 아래 "바꿀 조건"이 생기면 다시 정한다.
- 날짜: 2026-11-25
- 관련: [ADR-0005](ADR-0005-seller-sku-inventory-global-admission.md), [ADR-0066](ADR-0066-redis-admission-gate.md), [ADR-0069](ADR-0069-layered-naming-and-runtime-configuration.md), [EXP-079](../../03-experiments/EXP-079-expiry-sweep-bound.md), [EXP-086](../../03-experiments/EXP-086-admission-k-ttl-and-authorize-window.md), [EXP-090](../../03-experiments/EXP-090-admission-lock-failure-and-tombstone-size.md), [D-141](../../00-governance/decision-log.md)
- 추적: [Q-003](../../01-requirements/requirements-index.md#assumptions-and-open-questions), [D-149](../../00-governance/decision-log.md), [D-150](../../00-governance/decision-log.md), [Issue #641](https://github.com/Neo1228/ATLAS/issues/641)

## 문제

Admission의 K와 ticket TTL은 기본값도 상한도 없어 "요청이 준 값이 곧 운영 값"이고(`K=1,000,000,000`도 통과한다), payload·bot 방어·tombstone·잠금 실패 규칙은 문서로 정해진 적이 없다. 구매자 행동을 실측하지 못한다는 이유로 미뤄 왔는데, 미루는 동안 값이 없는 상태가 곧 가장 약한 보호다.

## 사실 (라벨: 측정 / 코드 읽기 / 가정)

1. **[코드 읽기]** K는 `seller_sku_admission_policy.max_active_checkouts`(CHECK > 0)로 Seller가 Seller-SKU별로 주고, ticket TTL은 Campaign 생성 요청의 `queueTicketTtlSeconds`(양수)다. 이 PR 전에는 둘 다 필수 값이고 상한이 없었다.
2. **[측정 · 가정 기반]** EXP-086은 구매자 300명·재고 60 모형(도착 30초 균등, 시작 전 포기 10%, 시작 후 포기 20%, T1 중앙값 10초, T2 중앙값 30초 — 모두 가정)에서 K 5~100 × TTL 30~300초를 칸당 5개 시드로 돌렸다. oversell·K 초과·순번 위반은 154회 모두 0이다.
3. **[코드 읽기]** `join`은 인증된 JWT의 account에서 `requireOwnBuyerRef`로 Buyer를 확인하고(`AdmissionController:38-39`), `GET /tickets/{id}`는 `AccountOwnershipInterceptor:84-88`이 소유를 검사한다. 같은 Seller-SKU·buyerRef의 ticket 수를 제한하는 코드는 없다(유일 제약은 `(campaign_id, idempotency_key)`와 `(seller_id, sku_id, sequence)`).
4. **[코드 읽기]** `AdmissionResponse.QueueTicket`은 9개 필드(`ticketId, campaignId, sellerId, skuId, buyerRef, sequence, status, expiresAt, checkoutSlotHeld`)다.
5. **[코드 읽기]** admission 모듈에는 ticket 삭제 쿼리가 없다. 주문 조회는 CAMPAIGN 주문마다 `admission.find(campaignId, ticketId)`를 부르고(`OrderServiceImpl:536,558`), 주문 수명 주기는 `releaseCheckoutSlot`을 부른다(`:422,480,488`). 그래서 주문이 가리키는 ticket 행을 지우면 주문 조회가 `TICKET_NOT_FOUND`가 된다(호출 경로를 읽은 것이며 삭제 후 조회를 실행해 보지는 않았다).
6. **[측정 · EXP-090]** ticket 행은 인덱스까지 약 473 B(1,000,000행, n=3). 정리 문장은 새 인덱스 없이는 정상 상태마다 표 전체를 훑어 550k행에서 1.4~1.8초, 부분 인덱스가 있으면 0.4~10 ms다.
7. **[측정 · EXP-090]** DB에 `lock_timeout`이 없어 잠금 대기는 `statement_timeout` 30초까지 간다. JdbcTemplate 경로 55P03은 `UncategorizedSQLException`, JPA 경로는 `CannotAcquireLockException`, 교착은 `PessimisticLockingFailureException`으로 올라오고, `GlobalExceptionHandler`에 잠금 처리가 없어 500이 된다(핸들러를 읽은 것). 한 Seller-SKU에 join 30 스레드를 몰면 지연 p99 1.36초, 최대 1.89초, 처리량 약 70/s다(공유 호스트, 풀 10).

## 대안

K·TTL 기본값 후보(EXP-086 주 격자 평균 [최소-최대], 구매자 300명·재고 60). "접수"는 `accepted` 주문 수, "대기 만료"는 `lost_queue_expired`, "시작 전 slot 점유"는 slot 시간 중 시작 전 포기가 차지한 비율이다.

| K × TTL | 접수 | 대기 만료 손실 | 대기 p95(가상 초) | 시작 전 slot 점유 | slot을 받고 못 시작 |
|---|---:|---:|---:|---:|---:|
| **50 × 120초 (채택)** | 47.6 [41-51] | 20.2 | 32.0 | 21.5% | 0.0 |
| 100 × 60초 | 48.0 [44-52] | 0.0 | 0.0 | 12.8% | 0.2 |
| 50 × 60초 | 47.6 [41-51] | 45.8 | 32.0 | 11.0% | 0.2 |
| 50 × 300초 | 47.6 [41-51] | 0.0 | 32.0 | 41.7% | 0.0 |
| 20 × 300초 (접수 최대) | 56.2 [53-59] | 97.8 | 686.2 | 31.7% | 0.0 |
| 20 × 120초 | 46.2 [37-52] | 181.8 | 192.1 | 11.3% | 0.0 |

- **목적(가정):** 접수 주문을 최대화하되 불변식 위반 0, 접수된 구매자의 대기 p95 ≤ 60초, 대기 만료 손실 ≤ 구매자의 10%(≤30명), 시작 전 포기가 slot 시간을 차지하는 비율 ≤ 25%, slot을 받고 못 시작한 구매자 ≤ 0.5명. 네 한계값은 EXP-086이 아니라 이 ADR이 정한 가정이다.
- 한계를 모두 지키는 칸은 `50×120`, `100×60`, `100×120`이다. 접수 평균은 47.6과 48.0으로 [최소-최대] 폭(10) 안에서 구분되지 않는다. 동률이면 **작은 K**를 택한다. `K=100`은 재고 60보다 커서 이 모형에서 접수된 구매자의 대기가 p50·p95 모두 0초라 admission이 없는 것과 효과가 같고 동시 checkout 상한도 주지 못한다. 같은 K에서 TTL은 접수가 같으므로 한계 안에서 더 짧은 쪽을 택하되 TTL 60초는 대기 만료가 45.8명(15%)이라 한계를 넘는다. 그래서 TTL 120초다.
- **접수 최대 칸 `20×300`**은 12개 정도의 시작 후 포기 예약이 10분 뒤 재고로 돌아올 때 느린 대기열에 아직 사람이 남아 있어 더 팔린다(EXP-086 관찰 1). 대가는 대기 p95 686초와 대기 만료 98명이다. 시작 후 포기율을 5%로 낮추면 접수 평균이 K=20 58.3 대 K=50 57.3으로 가까워지고 0%이면 둘 다 60/60이다(민감도, n=3). 즉 이 우위는 "시작 후 포기 20%"라는 가정에 기대며 실제 포기율이 높으면 다시 볼 후보다.

기타 대안: (가) 기본값을 두지 않고 요청에 맡기기(현재) — 오입력과 무제한 slot을 막지 못해 기각. (나) K를 재고 비율로 계산 — 이 모형은 재고 60 한 가지라 비율 근거가 없고 재고가 바뀌면 K가 따라 변해 설명하기 어려워 기각. (다) TTL을 대기 한도와 시작 한도로 분리 — 분리한 변형을 돌리지 않았다(EXP-086 관찰 2). 후속 실험 후보로 둔다.

## 결정

값 표. 모든 값은 환경 변수로 덮을 수 있다(속성 이름과 환경 변수는 `application.yml` 참조).

| # | 값 | 결정 | 위치와 상태 |
|---|---|---|---|
| 1 | Seller-SKU별 K 기본값 | **50** (Seller가 안 주면) | `atlas.admission.default-max-active-checkouts`, 코드·테스트 |
| 2 | K 상한 | 10,000 | `atlas.admission.max-active-checkouts-limit`, 코드·테스트. 튜닝 값이 아니라 오입력·남용 방지다. 부하 스크립트 `gate4.js`의 K 1,667을 허용하는 크기 |
| 3 | ticket TTL 기본값 | **PT2M** (Campaign 요청이 안 주면) | `atlas.admission.default-ticket-ttl`, 코드·테스트. 이미 있는 Campaign과 policy는 바뀌지 않는다 |
| 4 | ticket TTL 상한 | PT2H | `atlas.admission.ticket-ttl-limit`. `gate4.js`의 7,200초를 허용 |
| 5 | TTL 하한 권고 | 60초 이상 | 코드로 강제하지 않는다(요청 값 검사는 1초 이상만 본다). EXP-086에서 TTL 30초는 K≥50에서 slot을 받고도 시작하지 못한 구매자가 7.0~10.0명 |
| 6 | `lock_timeout` | **5초** | DB 접속 옵션 `-c lock_timeout=5000`(`application.yml`, `compose.yaml`) |
| 7 | 잠금 실패 응답 | 55P03·40P01 모두 **503 + Retry-After 1~3초**(트랜잭션은 롤백됨) | `GlobalExceptionHandler`, 코드·PostgreSQL 통합 테스트 |
| 8 | 재시도·포기 규칙 | 서버는 재시도하지 않는다. 클라이언트는 **같은 Idempotency-Key**로 `Retry-After` 뒤에 최대 3회 재시도하고 그다음에는 "혼잡"으로 안내하고 멈춘다 | 문서(클라이언트 계약). join은 멱등 키가 필수라 롤백된 요청의 재시도는 안전하다. 다른 경로의 재시도 안전성은 그 경로의 멱등 계약을 따르며 이 ADR이 확인하지 않았다 |
| 9 | ticket 응답 payload | 지금의 9개 필드 유지. 응답에 **넣지 않는다**: 다른 구매자의 `buyerRef`·ticket, 대기열 전체 길이·남은 인원, K·slot 사용 수, `idempotencyKey`, 내부 counter. `sequence`는 Seller-SKU 전역 번호이지 내 앞 인원이 아니다 | 문서. 필드를 줄이면 API 파괴 변경이라 하지 않는다. "예상 대기 시간·내 앞 인원" 필드는 실제 도착 분포를 재기 전에는 추가하지 않는다 |
| 10 | 인증 | join과 ticket 조회는 인증된 Buyer 소유로만 허용(**이미 구현**). 익명 join 없음 | 코드(변경 없음) |
| 11 | bot 방어: 요청률 | 앱 코드에 요청률 제한을 넣지 않고 게이트웨이 책임으로 둔다. 주체당 값(가정): ticket 조회 **2회/초**, join **1회/초(버스트 3)**. 정상 구매자는 2초 polling(0.5회/초)과 재진입 최대 2회(EXP-086 모형)다 | 문서. 키는 JWT `sub` 또는 토큰 단위. nginx가 `sub`를 읽는지는 확인하지 않았다 |
| 12 | bot 방어: 불변식 | 같은 `(sellerId, skuId, buyerRef)`의 live ticket(WAITING·CHECKOUT_ACTIVE·CHECKOUT_STARTED)은 **1개**. 더 만들려는 join은 409 | **결정했고 구현하지 않았다.** 이유와 후속은 아래 |
| 13 | tombstone(종료된 ticket) | V1은 **삭제하지 않는다**. 늘어나는 양은 행당 약 473 B | 아래 |

### 12번: live ticket 상한을 지금 구현하지 않는 이유

같은 구매자가 서로 다른 Idempotency-Key로 join을 반복하면 지금은 ticket과 K slot을 여러 개 잡는다(사실 3). 이것이 bot 방어에서 요청률보다 먼저 막아야 할 불변식이다. 그러나 join은 counter 행 잠금을 쥔 채 실행되고 한 Seller-SKU가 약 70/s이므로(사실 7), 잠금 안에서 `(seller_id, sku_id, buyer_ref)`를 세는 쿼리가 느리면 곧 상한 처리량이 줄어든다. 현재 인덱스는 `(seller_id, sku_id, sequence)`라 buyer 필터가 해당 Seller-SKU의 모든 ticket을 훑는다. 필요한 부분 인덱스 `(seller_id, sku_id, buyer_ref) WHERE status IN (live 3종)`는 마이그레이션과 잠금 안 비용 측정이 함께 필요해 "작고 검증 가능한 변경"이 아니다. 후속 작업 단위:

1. 속성 `atlas.admission.max-live-tickets-per-buyer`(기본 1) 추가, `AdmissionException.Code`에 상한 초과 코드 추가(409 매핑).
2. 마이그레이션으로 위 부분 인덱스 추가.
3. `join`의 counter 잠금 안, 만료 처리 뒤·순번 증가 전에 live 수 검사. 멱등 replay는 검사 앞에서 반환.
4. PostgreSQL 통합 테스트(변경 전 실패, 변경 후 통과)와 EXP-090 Part A harness로 상한 처리량 재측정.

### 13번: tombstone

- 정리하지 않는 이유: (a) 주문 조회와 수명 주기가 ticket 행을 읽으므로 status만 보고 지우면 주문 조회가 깨질 수 있다(사실 5). 안전한 삭제 조건은 "EXPIRED 또는 CHECKOUT_FAILED이고 어떤 `purchase_order`도 가리키지 않는 것"인데 이는 다른 모듈 테이블을 읽는 문장이라 admission 모듈 안에서 만들 수 없고 application 모듈의 maintenance worker가 맡아야 한다. (b) 늘어나는 양이 작다(473 B/행, 1 GiB ≈ 227만 행). (c) [D-141](../../00-governance/decision-log.md)이 reorder record를 같은 이유(측정한 크기, 정리 시 정확성 손상)로 보존한다.
- **정리를 시작하는 조건:** `admission_ticket` 테이블+인덱스가 1 GiB에 이르거나, ticket 행이 200만을 넘거나, 주문 조회 경로 없이 안전하게 지울 수 있는 계약이 생길 때.
- **그때 쓸 값(미리 정함, 구현 전):** 보존 30일(ticket 생성 시각 기준), 삭제 대상은 `EXPIRED`·`CHECKOUT_FAILED`이고 `purchase_order.ticket_id`에 없는 행만. 주기는 기존 scheduler 관례([EXP-079](../../03-experiments/EXP-079-expiry-sweep-bound.md), `AuthThrottleMaintenanceWorker`)를 따라 초기 지연 PT5M, 간격 PT10M, batch 500(상한 5,000). 속성은 `atlas.admission.tombstone.retention`(`P30D`), `purge-batch-size`(500), `purge-initial-delay`(PT5M), `purge-interval`(PT10M). 위치는 application 모듈 worker. **부분 인덱스 `(created_at, ticket_id) WHERE status IN ('EXPIRED','CHECKOUT_FAILED')`를 함께 넣는다**(EXP-090: 인덱스 없이는 정상 상태마다 표 전체를 훑어 550k행에서 1.4~1.8초, 있으면 0.4~10 ms, 35 MB). 삭제 후에는 같은 idempotency key로 재요청하면 새 ticket이 생기므로 key 유효 기간이 보존 기간(30일)이 된다는 점을 API 문서에 적는다. PostgreSQL 통합 테스트(주문이 가리키는 ticket은 남고 가리키지 않는 오래된 ticket만 삭제, batch 상한 준수)로 검증한다.

### 6~8번: 잠금 대기와 교착

- `lock_timeout` 5초의 근거: **측정** 한 Seller-SKU join 30 스레드의 지연 최대 1.89초(EXP-090). 2초는 이 최댓값에 가까워 정상 경합이 끊길 수 있고 5초는 약 2.6배다. `statement_timeout` 30초보다 먼저 끊어 잠금을 쥔 쪽이 멈췄을 때 Hikari 풀(10개)이 30초 동안 대기자로 차는 것을 막는다. 위 측정은 풀 10·호스트 부하가 높은 한 번의 값이라 이 여유가 충분한지는 확정하지 못했다.
- 교착: admission 한 트랜잭션은 Seller-SKU당 counter 행 하나만 잠가 admission끼리는 교착이 구조적으로 없다(`tryLockCounter`는 SKIP LOCKED라 sweep은 대기하지 않는다. 코드 읽기). 다른 모듈과의 순서(counter → 재고 행 대 재고 행 → counter)는 증명하지 못했다. 생겨도 PostgreSQL이 한쪽을 40P01로 중단하고 7번 규칙으로 처리된다(두 행 교착 한 가지를 실제 PostgreSQL에서 확인).
- Flyway: PostgreSQL 어댑터는 `pg_try_advisory_lock`류 재시도 문구(`Unable to acquire PostgreSQL advisory lock`)를 가지고 있어 `lock_timeout`이 마이그레이션 잠금 대기에 영향을 주지 않는 것으로 보이나 **replica 동시 기동으로 확인하지 않았다**. 문제가 생기면 `ATLAS_DB_URL`로 옵션을 빼면 된다.

## 검증

- `AdmissionLimitsTest`(범위 검사 3건), `AdmissionServiceTest`·`CampaignServiceTest`에 기본값 적용과 상한 거절 각 1건씩, `AdmissionPromotionCheckoutWindowIntegrationTest.omittedKAndTicketTtlUseConfiguredDefaultsAndLimitsAreEnforced`가 실제 PostgreSQL 행에 K 50·TTL 120초가 들어가고 상한 초과가 거절됨을 확인한다. `AdmissionLimitsDefaultsTest`는 테스트 classpath의 `application.yml`이 운영 파일을 가려서(테스트가 쓰는 값과 운영이 쓰는 값이 어긋날 수 있어) 운영 `application.yml`을 직접 읽어 코드 기본값과 같은지 본다.
- `LockFailureMappingIntegrationTest` 4건: 운영 접속 옵션이 서버에 `lock_timeout=5s`로 적용됨, JdbcTemplate·JPA 잠금 초과와 교착이 503 + Retry-After가 됨(교착은 두 트랜잭션 중 정확히 하나만 실패).
- 변이 확인: 핸들러를 변경 전으로 되돌리고 `lock_timeout`을 지우면 위 4건 중 해당 건이 실패하고, `AdmissionLimits`의 상한·기본값을 깨면 해당 단위·통합 테스트가 실패한다(결과는 PR 본문).

## 한계와 되돌리기

| 값 | 가정 | 바꿀 조건 | 되돌리기 |
|---|---|---|---|
| K 50 / TTL 120초 | 구매자 300명·재고 60, 시작 후 포기 20%, T1 p95 약 27초, T2 p95 약 80초, 품절이면 떠남, 대기 p95 ≤ 60초 등 한계값(이 ADR의 가정) | 첫 실제 판매에서 시작 후 포기율이 20%를 크게 넘거나(예: 접수가 재고의 80% 미만으로 끝남), T1 p99가 60초를 넘거나, 대기 만료 손실이 10%를 넘음. `load-tests/admission-sweep/run.sh`에 실측 분포를 넣어 다시 돌리고, 접수 우선이면 20×300을 후보로 둔다 | 환경 변수 `ATLAS_ADMISSION_DEFAULT_MAX_ACTIVE_CHECKOUTS`·`ATLAS_ADMISSION_DEFAULT_TICKET_TTL` 또는 Seller가 값을 명시. 코드 되돌리기는 PR revert(요청 필드는 선택이라 기존 클라이언트는 영향 없음) |
| 상한 10,000 / PT2H | 가장 큰 기존 사용(`gate4.js` K 1,667, TTL 7,200초)이 정상 사용의 상한 | 정당한 사용이 상한에 걸림 | 환경 변수 `ATLAS_ADMISSION_MAX_ACTIVE_CHECKOUTS_LIMIT`·`ATLAS_ADMISSION_TICKET_TTL_LIMIT` |
| `lock_timeout` 5초 | 정상 경합의 최대 대기가 1.9초 수준(공유 호스트 1회) | 정상 트래픽에서 55P03 503이 관찰됨(값을 올림), 잠금 점유자가 멈추는 사고가 다시 풀 고갈을 만듦(값을 내림) | `ATLAS_DB_URL`에서 `lock_timeout` 제거 |
| 503 매핑 | 롤백된 트랜잭션의 같은 키 재시도는 안전 | 재시도 폭주가 관찰됨 | `GlobalExceptionHandler`의 두 매핑 제거(500으로 복귀) |
| live ticket 1개, 요청률 값, tombstone 값 | 정상 구매자는 한 SKU에 동시에 ticket 하나, 2초 polling | 실제 bot 패턴 관찰, 테이블 1 GiB | 구현 전 결정이므로 되돌릴 코드가 없다 |

- K·TTL 기본값은 가정에 기댄 값이다. 구매자 분포를 실측한 값이 아니다.
- 요청률 값(2회/초, 1회/초)은 정상 구매자의 2초 polling에서 거꾸로 계산한 가정이며 게이트웨이에서 실제로 걸리는지는 확인하지 않았다.
- 인덱스 없는 정리 문장 비용은 합성 1,000,000행·모의 `purchase_order`·공유 호스트 값이다.
