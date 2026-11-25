# ADR-0088: Admission 종료 ticket 정리와 구매자별 live ticket 1개 규칙을 구현하고 그 값을 가정으로 적는다

- 상태: 채택(제안 PR, 병합은 통합 담당). 값은 운영 규모와 구매자 행동을 모르는 **가정**이며 아래 "바꿀 조건"이 생기면 다시 정한다.
- 날짜: 2026-11-25
- 관련: [ADR-0083](ADR-0083-admission-operational-values-and-lock-failure.md), [EXP-079](../../03-experiments/EXP-079-expiry-sweep-bound.md), [EXP-090](../../03-experiments/EXP-090-admission-lock-failure-and-tombstone-size.md), [EXP-092](../../03-experiments/EXP-092-admission-tombstone-purge-and-live-ticket.md)
- 추적: [Q-003](../../01-requirements/requirements-index.md#assumptions-and-open-questions), [D-158](../../00-governance/decision-log.md), [Issue #648](https://github.com/Neo1228/ATLAS/issues/648)

## 문제

ADR-0083은 종료된 ticket(tombstone)의 정리 값과 "같은 Seller-SKU에서 한 구매자의 live ticket은 1개" 규칙을 결정만 하고 구현하지 않았다. 이유는 (가) 주문 조회가 ticket을 읽어 status만 보고 지우면 `TICKET_NOT_FOUND`가 될 수 있고 (나) counter 잠금 안에서 buyer를 세는 비용을 재지 않았기 때문이다. 그 사이 ticket은 행당 약 473 B로 늘고 같은 구매자가 Idempotency-Key만 바꿔 동시에 join하면 ticket을 여러 개 잡는다(측정: 한 구매자 16 스레드 동시 join에서 200/200회 모두 ticket 2개 이상, EXP-092 H7).

## 사실 (라벨: 측정 / 코드 읽기 / 가정)

1. **[코드 읽기]** 주문 조회는 CAMPAIGN 주문마다 `admission.find`를 부르고(`OrderServiceImpl`), 주문은 영구 보존이라 이 읽기의 시간 창에 끝이 없다. `purchase_order.ticket_id`는 UNIQUE이고 FK가 없다. 주문은 `startCheckout`과 같은 transaction에서 만들어진다.
2. **[측정]** 정리 문장은 인덱스가 없으면 정상 상태마다 표 전체를 읽는다: 5,000,000행에서 101,247 buffer(1.1~3.1초). 조건 인덱스가 있으면 3~4 buffer(EXP-092 H1).
3. **[측정]** `status`만 조건인 인덱스는 주문이 가리켜 영영 못 지우는 오래된 행(P)이 쌓이면 P에 비례해 읽는다: P=100,000에서 500,780 buffer. `checkout_started_at IS NULL` 조건을 더하면 P와 무관하게 3 buffer(H2).
4. **[측정]** live 검사 문장은 인덱스가 있으면 종료 ticket 500,000개에서도 1~2 buffer, 없으면 14,624 buffer(H5).
5. **[측정]** 검사를 counter 잠금 안에서 하면 한 구매자 16 스레드 동시 join이 200/200회 live 1개로 끝난다(나머지 409). 규칙이 없으면 200/200회 live≥2(H7).
6. **[측정, 판단 불가]** 규칙이 join 지연(p50·p99)에 더하는 비율은 대조군의 실행 간 폭이 48%라 정하지 못했다(H6). 정리 loop를 같이 돌릴 때의 join 지연도 마찬가지다(H11).

## 대안

| 대안 | 결론 | 이유 |
|---|---|---|
| 정리하지 않음 | 기각 | 표가 끝없이 늘고 live 규칙 경쟁이 재현됨(가장 강한 반론: 규모가 작다. 그러나 경쟁은 이미 재현) |
| 보존 기간만으로 보호 | 기각 | 주문이 영구라 기간으로 안전하지 않음 |
| soft-delete 표시, archive 테이블 | 기각 | 표가 줄지 않거나 같은 조회 문제를 가짐 |
| anti-join만 | 기각 | P에 비례(H2) |
| anti-join + keyset cursor | 기각 | 위치를 기억할 상태가 필요(AGENTS.md: 숨은 mutable state 금지) |
| **anti-join + `checkout_started_at` + 조건 인덱스** | **채택** | 3 buffer, 상태 없음, 열 하나 |
| live 규칙: DB 유일 부분 인덱스 | 기각 | 기존 중복이 있으면 마이그레이션 실패(H8), 위반이 500, 성능 이점은 H6이 판단 불가라 입증하지 못함 |
| **live 규칙: counter 잠금 안 검사 + 부분 인덱스** | **채택** | 경쟁을 막음(H7), 문장 1~2 buffer(H5) |

## 결정

| # | 값 | 결정 | 위치와 상태 |
|---|---|---|---|
| 1 | 정리 대상 | `EXPIRED`·`CHECKOUT_FAILED`, `checkout_started_at IS NULL`, 생성 시각이 retention보다 오래됨, 어떤 `purchase_order`도 가리키지 않음 | `QueueRepository.purgeTombstones`, 통합 테스트 |
| 2 | 보존(retention) | **P30D**(생성 시각 기준). 하한 1일(워커 검증) | `atlas.admission.tombstone.retention`. **가정** |
| 3 | 실행 주기 | 초기 지연 PT5M, 간격 PT10M | `purge-initial-delay`, `purge-interval`. EXP-079·`AuthThrottleMaintenanceWorker` 관례, **가정** |
| 4 | batch | 500, 상한 5,000(범위 밖이면 기동 실패) | `purge-batch-size`. ADR-0083이 미리 정한 값, **가정** |
| 5 | 끄기 | `atlas.admission.tombstone.purge-enabled=false` | 기본 켬. 지운 뒤에는 되살릴 수 없으므로 끌 수 있게 함 |
| 6 | 다중 인스턴스 | `FOR UPDATE SKIP LOCKED` 한 문장. 별도 lease 없음 | H10 20/20(상한 15%, 약한 증거) |
| 7 | 위치 | 문장·서비스는 `atlas-admission`, scheduler 워커는 `atlas-application`(AuthThrottleMaintenanceWorker와 같은 자리). `purchase_order`는 SQL에서만 참조하고 Java 의존은 없음 | ModuleBoundaryTest 통과 |
| 8 | live ticket 상한 | **1**(Seller-SKU·구매자별 WAITING·CHECKOUT_ACTIVE·CHECKOUT_STARTED). 초과 join은 409 `LIVE_TICKET_LIMIT_REACHED`. 0이면 끔 | `atlas.admission.max-live-tickets-per-buyer`. 멱등 replay는 검사 앞에서 반환 |
| 9 | live 검사 위치 | counter 잠금 안, 순번 증가 전 | `AdmissionServiceImpl.join`. 변이(검사를 잠금 앞으로)로 통합 테스트 실패 확인 |
| 10 | 스키마(V4) | `checkout_started_at` 열 + backfill, 부분 인덱스 2개. 제약 없음 | `V4__admission_ticket_tombstone_and_live_indexes.sql` |

**"live"로 세는 상태:** `WAITING`·`CHECKOUT_ACTIVE`·`CHECKOUT_STARTED`(slot을 쥐었거나 줄을 서 있는 ticket). 결제를 제출하면(`CHECKOUT_SUBMITTED`), 만료되거나 실패하면(`EXPIRED`·`CHECKOUT_FAILED`) live가 아니므로 그 뒤에는 같은 Seller-SKU에 다시 join할 수 있다. 그래서 "앞선 주문이 결제 미해결인 동안 같은 SKU를 또 사려는" 정상 흐름은 막지 않는다: 첫 주문의 결제를 제출한 뒤 두 번째 ticket을 받으면 된다.

**기존 테스트 하나가 바뀌었다(의도한 변경).** `BuyerFlowIntegrationTest.buyerCannotStartAnotherPaymentWhileAnEarlierCampaignAttemptIsUnresolved`는 같은 구매자가 같은 Campaign에 두 ticket을 **동시에** 들고(join 두 번 뒤 checkout 시작 두 번) 있는 상황을 전제했다. 이것이 이 규칙이 막으려는 패턴(Idempotency-Key만 바꿔 ticket·slot을 여러 개 잡기)이라 두 번째 join이 409가 된다. 테스트의 목적은 "미해결 결제가 있으면 두 번째 결제를 거절"이므로 두 번째 join을 첫 결제 제출 뒤로 옮겼고 단정은 그대로다. 규칙을 약화하지 않았다. 제품 이유: 한 구매자가 한 번에 한 SKU의 slot을 하나만 쥐는 것이 bot 방어의 요점이고(ADR-0083 12번), 정상 구매자는 결제를 제출하는 즉시 다시 줄에 설 수 있다. 영향: 같은 SKU 두 개를 사려는 구매자는 첫 결제를 제출한 뒤에야 두 번째 ticket을 받는다.

보존 30일은 "key 유효 기간"이기도 하다: 삭제 뒤 같은 Idempotency-Key로 재요청하면 새 ticket이 생긴다. 이 점은 클라이언트 계약(문서)이다.

## 검증

- `AdmissionTombstonePurgeIntegrationTest` 2건(오래된 종료 ticket만 지우고 주문이 가리키는 것·`checkout_started_at`이 있는 것·최근 것·live는 남김, batch 상한 준수), `AdmissionLiveTicketIntegrationTest` 3건(동시 join이 live 1개, 멱등 replay는 거절하지 않음, 이전 ticket이 종료되면 다시 join 가능), `AdmissionLimitsDefaultsTest`(운영 `application.yml`과 코드 기본값 일치)를 추가·확장했다. 삭제한 테스트는 없다.
- 변이 7건(정리의 `NOT EXISTS`·`checkout_started_at` 조건·batch 상한·상태 조건·retention 방향 제거/반전, live 검사 끄기, 검사를 잠금 앞으로 이동)이 모두 테스트를 실패시키고 복원 일치(EXP-092).
- 되돌리기 시연: 속성을 끄면 정리가 멈추고, V4가 적용된 DB에 이전 jar를 다시 띄워도 기동한다(EXP-092, n=1).

## 한계와 되돌리기

| 값 | 가정 | 바꿀 조건 | 되돌리기 |
|---|---|---|---|
| 보존 30일, 10분 간격, batch 500 | 운영 규모·재요청 기간을 모름 | 표가 1 GiB에 이르거나 정리 1회가 1초를 넘음, 지연 재요청이 30일보다 길게 필요함 | 속성(`ATLAS_ADMISSION_TOMBSTONE_*`), 끄기는 `PURGE_ENABLED=false`. 지운 행은 복구되지 않음 |
| live ticket 1개 | 정상 구매자는 한 Seller-SKU에 ticket 하나 | 409 `LIVE_TICKET_LIMIT_REACHED` 비율이 높음, 실제 재진입 패턴이 다름 | `ATLAS_ADMISSION_MAX_LIVE_TICKETS_PER_BUYER=0` 또는 2 이상 |
| 규칙의 지연 비용 | **확인하지 못함** | 운영에서 join p99·503 비율 증가 | 위 속성 0 |
| `SKIP LOCKED` 다중 인스턴스 안전 | 20회 0건(상한 15%) | 중복 삭제·오류 관찰 | `PURGE_ENABLED=false` 후 한 인스턴스만 켬 |

- 확인하지 못한 것: H6·H11(위), H11의 오류 1건 원인, 실제 `purchase_order` 쓰기 경로와 정리가 동시에 도는 상태, 큰 운영 DB에서의 V4 적용 시간(5M행 합성 값만 있음).
- 열 `checkout_started_at`은 `startCheckout`이 쓴다. 마이그레이션 전에 시작된 checkout은 backfill이 채우고, 채우지 못해도 `NOT EXISTS`가 지켜 정확성은 같고 읽기만 늘어난다.
