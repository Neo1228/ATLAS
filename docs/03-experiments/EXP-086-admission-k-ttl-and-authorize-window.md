# EXP-086: Admission K·ticket TTL 민감도와 PayPal AUTHORIZE 유효 기간 대비 repo 창

> 정리 안내: 이 실험의 실행 파일(`load-tests/admission-sweep/`, `load-tests/paypal-real-fault/authorization-window.mjs`)은 저장소 오염을 막으려고 삭제했다. 하네스의 동작과 실행 방법은 이 문서 끝 "삭제한 파일의 내용" 절에 적었다. 파일은 커밋 `546d8002` 이후 이력에서 복원할 수 있다.

**상태:** 로컬 합성 구매자 모형 · 실제 서비스와 PostgreSQL 위에서 가상 시계로 실행 · 건수 기준 결과(시간은 가상 시간) · 운영 K/TTL 값과 운영 SLO의 근거 아님 · 연결: [Q-003, Q-004](../01-requirements/requirements-index.md#assumptions-and-open-questions), [D-025~D-030](../00-governance/decision-log.md), [D-145](../00-governance/decision-log.md), [Issue #633](https://github.com/Neo1228/ATLAS/issues/633)

## 질문과 가설 (측정 전에 적음)

Q-003과 Q-004가 열려 있는 이유는 값이 가정이기 때문이다. 이 실험은 repo 안에서 **측정으로 답할 수 있는 질문**만 다루고, 답할 수 없는 것은 아래 "측정하지 않은 것"에 적는다.

| # | 가설 | 틀렸다고 판단하는 기준 |
|---|---|---|
| H1 | K가 클수록 접수 주문 수는 늘고, 재고(60)나 구매자가 한계가 되는 K에서 더는 늘지 않는다. | K를 키워도 접수 주문이 단조 증가하지 않음 |
| H2 | TTL이 길수록 대기 중 만료로 잃는 구매자가 줄고 접수 주문이 늘지만, 시작 전 포기 구매자가 slot을 TTL만큼 붙잡아 낭비가 는다. | TTL을 늘려도 대기 만료가 줄지 않음 |
| H3 | oversell, K 초과, 순번 위반은 K·TTL과 무관하게 모든 칸에서 0이다(불변식). | 한 칸이라도 0이 아님 |
| H4 | slot 낭비의 대부분은 TTL이 아니라, 시작 뒤 포기한 buyer가 10분 checkout window 동안 slot과 재고를 잡는 데서 나온다(코드 읽기로 세운 가설: 아래 "코드에서 확인한 사실"). | 시작 후 포기율을 0으로 내려도 낭비율이 그대로임 |
| H5 | PayPal authorization의 유효 기간은 repo의 10분·30분 창보다 훨씬 길다(문서상 29일, honor 3일). | 문서나 Sandbox 응답이 더 짧음을 보임 |
| H6 | Sandbox가 돌려주는 `expiration_time`은 `create_time + 29일`이고, honor period는 응답에 필드로 나오지 않는다. | 다른 값이 관찰됨 |

## 측정하지 않은 것 (처음부터 범위 밖)

- **운영에 쓸 K와 TTL 값.** 구매자 행동(생각 시간, 포기율, 재진입)은 이 repo에 실측이 없어 모형 가정이다. 값을 하나 고르는 근거가 아니라 **값이 결과를 어떻게 움직이는지**만 본다.
- **bot·인증 정책.** admission은 인증 경계 안에서만 열리고, 이 모형은 구매자를 모두 정상 사용자로 둔다. 로그인 시도 제한은 `atlas.auth.throttle.*`로 별도다.
- **실시간 지연과 처리량.** 공유 머신이고 시계가 가상이라 시간 값은 맥락일 뿐이다.
- **PayPal AUTHORIZE의 운영 분포(p50/p95/p99)와 SLO.** 실제 buyer가 없다. D-030이 요구하는 분포는 여전히 비어 있다.

## 코드에서 확인한 사실 (측정 아님)

| 항목 | 값과 동작 | 위치 |
|---|---|---|
| K | Seller-SKU별 `maxActiveCheckouts`(정수 1 이상). 기본값 없음, 요청이 값을 준다. | `AdmissionServiceImpl.configureSellerSkuPolicy`, `admission_policy` |
| ticket TTL | Campaign별 `queueTicketTtlSeconds`(양수). 기본값 없음. 부하 스크립트 값은 `gate4.js`의 7,200초(K=1,667 또는 1)뿐이다. | `CampaignServiceImpl.create`, `load-tests/gate4.js` |
| TTL의 첫 번째 역할 | `join`한 ticket의 `expires_at = join 시각 + TTL`. WAITING 상태로 TTL이 지나면 EXPIRED, 재진입은 새 key로 전역 대기열 **끝**. | `AdmissionServiceImpl.join`, `QueueRepository.expireDueTicketsForSellerSku` |
| TTL의 두 번째 역할 | 승격되면 `expires_at = slot 획득 시각 + TTL`로 다시 계산. 즉 TTL은 대기 한도이자 "slot을 받은 뒤 checkout을 시작하기까지" 한도다. | `QueueRepository.activateWaitingTicket`, `AdmissionPromotionCheckoutWindowIntegrationTest` |
| TTL이 닿지 않는 구간 | admission 만료 sweep 대상은 `WAITING`, `CHECKOUT_ACTIVE`뿐이다. `startCheckout` 뒤(`CHECKOUT_STARTED`)의 slot은 첫 AUTHORIZE 제출(`INITIAL_AUTHORIZE_SUBMITTED`) 또는 주문 만료(`CHECKOUT_WINDOW_EXPIRED`, `atlas.checkout-window` 기본 PT10M)에서만 풀린다. TTL이 아니다. | `QueueRepository.findDueTicketCandidates`, `OrderServiceImpl.startCheckout`·`expire`, `application.yml` |
| 재고 hold | `startCheckout`이 재고를 예약하고, 제출되지 않은 주문은 10분 뒤 sweep이 예약을 반환한다. | `OrderServiceImpl.expire` |
| 순번 | `admission_counter.last_sequence`를 `FOR UPDATE`로 잠그고 증가. 승격은 `sequence` 순, 만료되지 않은 WAITING만. | `QueueRepository.lockCounter`, `TicketRepository.findWaitingForSellerSku` |
| 재고 불변식 | DB 제약 `available + reserved + claimed = initial_capacity`, `available >= 0`. 앱이 잘못 예약해도 DB가 거절한다. | `inventory_pool` (V1 baseline) |

## 환경과 방법

- 하드웨어: macOS arm64 개발 머신(Colima 8 CPU, 약 12.6 GiB를 다른 작업과 **공유**). 주 격자 시작 때와 민감도 실행 끝에 잰 `uptime`의 1분 load average는 4.95 → 9.14, 다른 에이전트의 Gradle·Testcontainers가 동시에 돌고 있었다. 그래서 `wallSeconds`는 맥락으로만 적고(주 격자 100칸의 합 15.2분, 칸당 최대 18.6초, 가상 시간으로는 칸당 최대 25.9분) 결론은 **건수와 불변식**에만 둔다.
- 소프트웨어: JDK 25, Spring Boot 앱의 실제 `AdmissionService`, `OrderService`(재고 예약, `submitPayment`), `PostgreSQL 18.6`(Testcontainers, 칸마다 새 Seller·SKU·Campaign). PayPal만 로컬 MockWebServer 대역(`PayPalIntegrationTestSupport`)이다. 이 모형은 PayPal 응답 지연을 모사하지 않는다.
- 시계: 앱의 `Clock` 빈을 가상 시계로 바꾸고 1초 tick마다 `expireDueTickets()`와 `expireDueOrders()`를 호출한다(운영 `expiry-poll-interval` PT1S와 같은 주기). 단일 스레드 이산 사건 시뮬레이션이라 같은 시드면 구매자 난수열이 모든 칸에서 같다(짝 비교).
- 실행: 삭제한 `load-tests/admission-sweep/run.sh`(harness를 test 소스에 임시 복사해 Gradle로 실행하고 지움, 아래 절). 일반 `check`에 들어가지 않는다(저장소 테스트 정리 기준 4번: 실험·부하는 `check` 밖).
- 격자: K ∈ {5, 10, 20, 50, 100} × TTL ∈ {30, 60, 120, 300}초 × 시드 5개 = **100회**(칸당 n=5). 민감도: 시작 후 포기율 0%와 5%, K ∈ {5, 20, 50} × TTL ∈ {30, 120, 300} × 시드 3개 = 27회와 27회.
- 산출물: 칸당 JSON 1줄(삭제한 `aggregate.py`가 표로 묶음). 원자료(jsonl)는 저장소에 커밋하지 않고 이 문서의 표가 정본이다(EXP-033과 같은 정책).

### 구매자 모형 (모두 가정, 값의 출처는 실측이 아님)

| 항목 | 값 | 이유와 성격 |
|---|---|---|
| 구매자 N, 재고 S | 300명, 재고 60 | 수요가 재고의 5배인 hot SKU. 가정 |
| 도착 | 30초 안에 균등(300명을 시드로 뽑아 정렬) | 판매 시작 직후 몰림. 가정 |
| slot을 받은 뒤 알아차리는 시간 | 승격된 경우 0~2초 균등(2초 polling), join 즉시 승격이면 0 | 프론트 polling 주기 가정 |
| 생각 시간 T1 (알림 → `startCheckout`) | 로그정규, 중앙값 10초, σ=0.6 (p95 약 27초) | 화면 확인 후 클릭. 가정 |
| 생각 시간 T2 (예약 → 결제 제출) | 로그정규, 중앙값 30초, σ=0.6 (p95 약 80초) | 주소·결제 수단 확인. 가정 |
| 시작 전 포기 | 10% (slot을 받고도 시작하지 않음, 조용히 사라짐) | 가정 |
| 시작 후 포기 | 20% (예약만 하고 제출하지 않음, 주문은 10분 뒤 만료) | 가정. 민감도로 0%, 5%도 돌림 |
| 만료 뒤 재진입 | 70% 확률로 새 key 재진입, 최대 2회. 알아차린 뒤 1~3초 | 가정 |
| 품절 | `startCheckout`에서 재고 예약 실패를 알게 되면 떠남(재시도·새로고침 없음) | 가정. 한계 절 참고 |
| 종료 | 모든 구매자가 확정되거나 가상 45분. 시작 후 포기 주문의 10분 만료도 기다림 | |

### 지표 정의 (칸마다 구매자 300명이 정확히 하나의 최종 상태에 속한다)

- `accepted`: `submitPayment`가 `PAYMENT_PENDING` 이상으로 돌아온 구매자(첫 AUTHORIZE 제출이 일어나 slot이 풀림). capture는 범위 밖이라 PayPal 대역은 "승인 대기"까지만 간다.
- `sold_out`: `startCheckout`에서 재고 예약이 거절됨(`INVENTORY_UNAVAILABLE`).
- `lost_queue_expired`: 대기 ticket이 TTL로 만료되고 재진입 기회를 다 썼거나 재진입하지 않음.
- `lost_slow_start`: slot을 받았지만 TTL 안에 시작하지 못함(서비스가 `CHECKOUT_SLOT_UNAVAILABLE`로 거절)이고 재진입 기회를 다 씀.
- `abandoned_before_start`, `abandoned_after_start`: 모형이 정한 자발 포기.
- `expiredWaitingTickets`, `expiredHoldingTickets`: DB에서 센 EXPIRED ticket 수(재진입 ticket 포함). 앞은 slot을 받지 못한 채, 뒤는 slot을 받은 뒤 만료.
- `slotWastePercent`: 모든 slot 보유 구간(초) 중 `accepted`로 끝나지 않은 비율(포기, 만료, 품절 확인 포함).
- `waitToSlotAcceptedP50S/P95S`: `accepted` 구매자의 첫 join부터 최종 slot 획득까지 가상 초(재진입 대기 포함).
- `queueOrderViolations`: 순번이 더 작은 ticket이 아직 대기 중(만료 전)인데 더 큰 순번이 먼저 slot을 얻은 ticket 쌍의 수. 검사 SQL이 실제로 위반을 잡는지는 합성 행으로 확인했다(위반 2건 주입 → 2 칸 검출, 정상 집합 → 0).
- `maxSlotsHeld`: 매 tick `checkout_slot_held=true` 행 수의 최대. K를 넘으면 `slotLimitExceeded`.
- `oversell`: `max(reserved+claimed, 살아있는 reservation 수) - S`가 양수이거나 `available < 0`.

## 결과 (Q-003)

### 불변식 (H3)

| 격자 | 실행 수 | oversell | K 초과 | 순번 위반 | 서비스 오류 | 미확정 구매자 |
|---|---|---|---|---|---|---|
| 주 격자 | 100 | 0 | 0 | 0 | 0 | 0 |
| 시작 후 포기 0% | 27 | 0 | 0 | 0 | 0 | 0 |
| 시작 후 포기 5% | 27 | 0 | 0 | 0 | 0 | 0 |

합계 원자료: 주 격자 `{"oversell": 0, "queueOrderViolations": 0, "slotLimitExceeded": 0, "errors": 0, "unresolved": 0}`, 0% `{"oversell": 0, "queueOrderViolations": 0, "slotLimitExceeded": 0, "errors": 0, "unresolved": 0}`, 5% `{"oversell": 0, "queueOrderViolations": 0, "slotLimitExceeded": 0, "errors": 0, "unresolved": 0}`. 154회 모두 구매자 최종 상태 합이 300이고, 최종 `inventory_pool`의 `available + reserved + claimed`가 60이며 `reserved + claimed`가 살아있는 reservation 수와 같고, 접수 수가 재고(60) 이하다(가장 큰 접수 수는 60, 시작 후 포기 0% 칸. 주 격자는 59). 154회 모두 `maxSlotsHeld`가 K에 도달했고 넘지 않았다(K 한도가 실제로 걸렸다는 뜻이다). **H3은 이 모형 범위에서 지지된다.** 단, oversell은 DB 제약이 막는 불변식이라 K·TTL이 못 깨는 것이 예상된 결과이고, 이 실험의 정보는 K·TTL이 순번과 slot 한도를 깨지 않는다는 쪽이다.

### 접수 주문과 재고 (H1, H2)

접수 주문 수(평균 [최소-최대], 시드 n=5, 재고 60):

| K \ TTL | 30s | 60s | 120s | 300s |
|---|---|---|---|---|
| 5 | 9.4 [7-13] | 13.6 [9-18] | 13.0 [7-21] | 27.8 [14-37] |
| 10 | 18.4 [17-21] | 26.2 [20-32] | 33.0 [19-42] | 45.4 [31-55] |
| 20 | 36.4 [28-42] | 46.2 [38-52] | 46.2 [37-52] | 56.2 [53-59] |
| 50 | 47.4 [42-50] | 47.6 [41-51] | 47.6 [41-51] | 47.6 [41-51] |
| 100 | 48.0 [44-52] | 48.0 [44-52] | 48.0 [44-52] | 48.0 [44-52] |

품절을 알게 된 구매자 수(`sold_out`):

| K \ TTL | 30s | 60s | 120s | 300s |
|---|---|---|---|---|
| 5 | 0.0 [0-0] | 0.0 [0-0] | 0.0 [0-0] | 0.0 [0-0] |
| 10 | 0.0 [0-0] | 0.0 [0-0] | 0.0 [0-0] | 1.8 [0-9] |
| 20 | 0.0 [0-0] | 10.2 [0-25] | 48.2 [0-106] | 112.6 [83-132] |
| 50 | 125.2 [114-137] | 169.6 [160-177] | 192.2 [185-200] | 210.2 [203-213] |
| 100 | 199.2 [190-204] | 210.0 [203-213] | 210.2 [203-213] | 210.2 [203-213] |

대기 ticket 만료로 잃은 구매자 수(`lost_queue_expired`, 재진입 소진 후):

| K \ TTL | 30s | 60s | 120s | 300s |
|---|---|---|---|---|
| 5 | 286.8 [283-289] | 282.8 [280-286] | 281.4 [273-288] | 263.2 [254-276] |
| 10 | 275.6 [274-277] | 265.6 [261-270] | 256.2 [249-269] | 234.4 [218-249] |
| 20 | 249.2 [243-253] | 226.0 [207-241] | 181.8 [118-240] | 97.8 [88-124] |
| 50 | 87.6 [82-94] | 45.8 [40-56] | 20.2 [14-30] | 0.0 [0-0] |
| 100 | 1.0 [0-5] | 0.0 [0-0] | 0.0 [0-0] | 0.0 [0-0] |

slot을 받고도 TTL 안에 시작하지 못해 잃은 구매자 수(`lost_slow_start`):

| K \ TTL | 30s | 60s | 120s | 300s |
|---|---|---|---|---|
| 5 | 0.4 [0-1] | 0.0 [0-0] | 0.0 [0-0] | 0.0 [0-0] |
| 10 | 0.4 [0-2] | 0.0 [0-0] | 0.0 [0-0] | 0.0 [0-0] |
| 20 | 2.2 [0-5] | 0.0 [0-0] | 0.0 [0-0] | 0.0 [0-0] |
| 50 | 7.0 [3-9] | 0.2 [0-1] | 0.0 [0-0] | 0.0 [0-0] |
| 100 | 10.0 [5-14] | 0.2 [0-1] | 0.0 [0-0] | 0.0 [0-0] |

### 포기와 만료 ticket

시작 전 포기(`abandoned_before_start`):

| K \ TTL | 30s | 60s | 120s | 300s |
|---|---|---|---|---|
| 5 | 0.6 [0-2] | 1.2 [0-3] | 1.4 [0-4] | 2.4 [1-4] |
| 10 | 1.0 [0-2] | 2.6 [1-3] | 3.6 [2-6] | 6.8 [5-10] |
| 20 | 3.0 [1-5] | 6.0 [5-8] | 10.8 [4-16] | 19.4 [16-24] |
| 50 | 20.2 [18-28] | 24.4 [22-29] | 27.6 [25-34] | 29.8 [27-37] |
| 100 | 29.8 [27-37] | 29.8 [27-37] | 29.8 [27-37] | 29.8 [27-37] |

시작 후 포기(`abandoned_after_start`, 예약은 10분 뒤 반환):

| K \ TTL | 30s | 60s | 120s | 300s |
|---|---|---|---|---|
| 5 | 2.8 [2-3] | 2.4 [2-4] | 4.2 [2-5] | 6.6 [4-9] |
| 10 | 4.6 [3-6] | 5.6 [4-8] | 7.2 [4-9] | 11.6 [8-15] |
| 20 | 9.2 [6-13] | 11.6 [8-16] | 13.0 [8-19] | 14.0 [10-21] |
| 50 | 12.6 [10-18] | 12.4 [9-19] | 12.4 [9-19] | 12.4 [9-19] |
| 100 | 12.0 [8-16] | 12.0 [8-16] | 12.0 [8-16] | 12.0 [8-16] |

DB의 EXPIRED ticket 중 slot을 받지 못한 것(`expiredWaitingTickets`):

| K \ TTL | 30s | 60s | 120s | 300s |
|---|---|---|---|---|
| 5 | 631.8 [613-654] | 624.0 [603-649] | 619.6 [600-642] | 594.2 [570-630] |
| 10 | 609.2 [592-631] | 590.4 [573-609] | 574.0 [546-608] | 539.4 [497-581] |
| 20 | 557.2 [537-582] | 522.0 [487-562] | 458.6 [382-554] | 328.6 [267-418] |
| 50 | 290.8 [260-317] | 158.2 [137-175] | 66.8 [36-93] | 0.0 [0-0] |
| 100 | 4.8 [0-21] | 0.0 [0-0] | 0.0 [0-0] | 0.0 [0-0] |

slot을 받은 뒤 만료된 것(`expiredHoldingTickets`):

| K \ TTL | 30s | 60s | 120s | 300s |
|---|---|---|---|---|
| 5 | 4.2 [3-6] | 3.6 [2-5] | 5.6 [5-7] | 9.0 [7-10] |
| 10 | 7.0 [4-11] | 8.2 [5-11] | 10.8 [9-13] | 18.4 [16-20] |
| 20 | 17.2 [12-27] | 17.6 [14-21] | 23.8 [23-25] | 33.4 [27-39] |
| 50 | 46.2 [38-53] | 37.4 [32-43] | 40.0 [35-44] | 42.2 [36-47] |
| 100 | 65.0 [58-78] | 42.4 [35-49] | 41.8 [35-48] | 41.8 [35-48] |

생성된 ticket 총수(`tickets`, 재진입 포함. 구매자는 300명):

| K \ TTL | 30s | 60s | 120s | 300s |
|---|---|---|---|---|
| 5 | 645.4 [624-665] | 641.2 [618-666] | 638.2 [622-657] | 631.0 [611-654] |
| 10 | 634.6 [616-654] | 624.8 [606-643] | 617.8 [597-645] | 605.0 [579-632] |
| 20 | 610.8 [591-632] | 596.0 [574-622] | 576.8 [541-614] | 530.8 [479-594] |
| 50 | 509.6 [485-535] | 412.8 [397-427] | 346.6 [322-371] | 300.0 [300-300] |
| 100 | 317.0 [312-323] | 300.4 [300-302] | 300.0 [300-300] | 300.0 [300-300] |

### slot 낭비와 대기 (가상 시간)

slot 낭비 비율(%, `accepted`로 끝나지 않은 slot 시간 / 전체 slot 시간. 나머지는 느린 시작으로 TTL 만료, 느린 제출 등):

| K \ TTL | 30s | 60s | 120s | 300s |
|---|---|---|---|---|
| 5 | 78.3 [66.2-84.4] | 66.2 [57.2-84.7] | 78.9 [59.9-90.4] | 77.1 [66.6-89.5] |
| 10 | 73.1 [63.3-84.6] | 72.1 [60.7-84.1] | 74.4 [58.1-87.6] | 80.6 [74.3-88.3] |
| 20 | 75.7 [65.9-86.3] | 76.4 [69.4-85.5] | 81.1 [75.3-87.8] | 85.1 [82.9-89.0] |
| 50 | 81.5 [77.1-87.9] | 82.5 [77.6-88.9] | 85.0 [80.7-90.0] | 89.1 [86.0-92.3] |
| 100 | 84.2 [79.2-87.7] | 84.9 [80.0-88.4] | 86.6 [82.4-89.5] | 90.1 [87.1-91.9] |

slot 시간 중 **시작 후 포기**(예약만 하고 10분 뒤 만료)가 차지한 비율(%):

| K \ TTL | 30s | 60s | 120s | 300s |
|---|---|---|---|---|
| 5 | 76.3 [63.0-83.1] | 63.0 [53.4-82.7] | 73.5 [42.9-90.4] | 64.9 [48.6-79.8] |
| 10 | 71.2 [61.5-81.3] | 68.9 [56.5-80.7] | 67.5 [46.6-82.3] | 62.2 [46.0-73.5] |
| 20 | 72.3 [62.8-82.6] | 71.1 [59.4-83.0] | 64.2 [45.0-84.3] | 45.4 [35.6-59.3] |
| 50 | 60.8 [54.9-71.6] | 54.4 [46.3-68.2] | 47.1 [39.7-61.6] | 34.5 [27.5-47.3] |
| 100 | 53.2 [42.8-62.5] | 50.9 [41.1-59.0] | 45.2 [36.1-53.5] | 33.9 [26.5-41.2] |

slot 시간 중 **시작 전 포기**(slot을 받고도 시작하지 않아 TTL까지 보유)가 차지한 비율(%):

| K \ TTL | 30s | 60s | 120s | 300s |
|---|---|---|---|---|
| 5 | 0.8 [0.0-2.5] | 3.2 [0.0-8.8] | 5.5 [0.0-17.0] | 12.2 [4.3-19.9] |
| 10 | 0.8 [0.0-2.0] | 3.2 [1.5-4.2] | 6.9 [3.4-11.5] | 18.2 [12.0-28.3] |
| 20 | 1.3 [0.3-2.6] | 3.8 [2.5-5.9] | 11.3 [3.5-17.7] | 31.7 [24.9-39.1] |
| 50 | 5.0 [3.5-7.3] | 11.0 [8.4-14.0] | 21.5 [15.9-27.1] | 41.7 [34.1-49.9] |
| 100 | 6.7 [5.5-8.5] | 12.8 [10.7-16.1] | 22.7 [19.3-27.8] | 42.2 [37.5-49.0] |

slot 시간 중 **품절 확인**(`startCheckout`에서 재고 없음을 알 때까지)이 차지한 비율(%):

| K \ TTL | 30s | 60s | 120s | 300s |
|---|---|---|---|---|
| 5 | 0.0 [0.0-0.0] | 0.0 [0.0-0.0] | 0.0 [0.0-0.0] | 0.0 [0.0-0.0] |
| 10 | 0.0 [0.0-0.0] | 0.0 [0.0-0.0] | 0.0 [0.0-0.0] | 0.2 [0.0-0.8] |
| 20 | 0.0 [0.0-0.0] | 1.6 [0.0-4.5] | 5.6 [0.0-12.7] | 8.0 [4.9-10.2] |
| 50 | 12.3 [10.3-13.3] | 16.9 [12.2-19.2] | 16.4 [12.5-18.6] | 12.9 [10.8-14.9] |
| 100 | 19.0 [16.9-21.2] | 21.0 [17.3-25.2] | 18.7 [16.2-22.1] | 13.9 [12.5-16.3] |

`accepted` 구매자의 첫 join → 최종 slot 획득 p50(가상 초):

| K \ TTL | 30s | 60s | 120s | 300s |
|---|---|---|---|---|
| 5 | 24.4 [0.0-62.3] | 62.7 [41.3-92.3] | 71.8 [0.0-152.9] | 351.7 [87.3-702.1] |
| 10 | 29.1 [26.4-30.0] | 56.8 [44.4-60.0] | 121.5 [44.8-237.9] | 240.4 [114.0-349.9] |
| 20 | 29.0 [26.9-29.9] | 51.5 [34.6-59.9] | 57.0 [32.8-71.3] | 92.0 [50.6-162.2] |
| 50 | 0.0 [0.0-0.0] | 0.0 [0.0-0.0] | 0.0 [0.0-0.0] | 0.0 [0.0-0.0] |
| 100 | 0.0 [0.0-0.0] | 0.0 [0.0-0.0] | 0.0 [0.0-0.0] | 0.0 [0.0-0.0] |

같은 값의 p95(가상 초):

| K \ TTL | 30s | 60s | 120s | 300s |
|---|---|---|---|---|
| 5 | 95.0 [94.1-96.1] | 180.0 [160.6-185.8] | 244.5 [87.3-364.0] | 860.8 [782.3-905.3] |
| 10 | 95.8 [95.5-96.1] | 180.5 [171.0-185.4] | 305.8 [241.6-365.1] | 850.6 [768.7-904.9] |
| 20 | 95.5 [95.1-96.3] | 170.5 [150.8-185.3] | 192.1 [120.0-296.8] | 686.2 [584.1-816.4] |
| 50 | 27.8 [24.8-29.9] | 32.0 [24.2-40.5] | 32.0 [24.2-40.5] | 32.0 [24.2-40.5] |
| 100 | 0.0 [0.0-0.0] | 0.0 [0.0-0.0] | 0.0 [0.0-0.0] | 0.0 [0.0-0.0] |

마지막 접수까지 걸린 가상 시간(초):

| K \ TTL | 30s | 60s | 120s | 300s |
|---|---|---|---|---|
| 5 | 176.4 [140.4-213.8] | 260.7 [207.4-307.1] | 291.4 [109.2-452.0] | 923.8 [839.2-976.7] |
| 10 | 198.9 [161.7-288.0] | 258.3 [238.2-280.9] | 375.1 [296.6-450.8] | 955.8 [865.6-1009.7] |
| 20 | 203.0 [172.9-288.0] | 255.3 [227.7-280.9] | 305.9 [231.6-448.6] | 824.3 [669.2-924.7] |
| 50 | 143.5 [124.3-172.9] | 148.7 [124.3-172.9] | 148.7 [124.3-172.9] | 148.7 [124.3-172.9] |
| 100 | 120.7 [102.4-172.9] | 120.7 [102.4-172.9] | 120.7 [102.4-172.9] | 120.7 [102.4-172.9] |

### 민감도: 시작 후 포기율 (H4)

접수 주문 수(시드 n=3. 20%(기준) 행은 주 격자의 시드 1~3만 다시 묶음):

| 시작 후 포기율 | K | TTL 30s | TTL 120s | TTL 300s |
|---|---|---|---|---|
| 0% | 5 | 16.0 [14-18] | 43.0 [40-48] | 60.0 [60-60] |
| 0% | 20 | 59.3 [58-60] | 60.0 [60-60] | 60.0 [60-60] |
| 0% | 50 | 60.0 [60-60] | 60.0 [60-60] | 60.0 [60-60] |
| 5% | 5 | 15.3 [14-18] | 38.3 [32-48] | 57.0 [53-60] |
| 5% | 20 | 55.3 [51-59] | 57.0 [54-60] | 58.3 [57-60] |
| 5% | 50 | 57.3 [55-60] | 57.3 [55-60] | 57.3 [55-60] |
| 20%(기준) | 5 | 9.7 [8-13] | 11.7 [7-17] | 23.7 [14-35] |
| 20%(기준) | 20 | 34.3 [28-42] | 42.7 [37-48] | 57.3 [54-59] |
| 20%(기준) | 50 | 45.7 [42-49] | 45.3 [41-50] | 45.3 [41-50] |

slot 낭비 비율(%):

| 시작 후 포기율 | K | TTL 30s | TTL 120s | TTL 300s |
|---|---|---|---|---|
| 0% | 5 | 3.8 [0.0-7.1] | 9.1 [0.0-21.4] | 44.8 [43.6-45.7] |
| 0% | 20 | 11.4 [4.0-19.6] | 60.9 [57.5-64.7] | 78.0 [75.4-80.9] |
| 0% | 50 | 51.1 [46.8-53.5] | 70.0 [68.0-73.1] | 81.4 [79.8-84.0] |
| 5% | 5 | 17.7 [0.0-48.9] | 22.7 [0.0-39.3] | 46.4 [43.6-48.4] |
| 5% | 20 | 38.0 [25.0-57.5] | 68.5 [64.7-71.5] | 80.1 [79.7-80.9] |
| 5% | 50 | 61.5 [53.5-66.6] | 74.8 [73.1-77.1] | 83.6 [82.6-84.3] |
| 20%(기준) | 5 | 75.8 [66.2-81.4] | 84.9 [78.6-90.4] | 83.0 [75.5-89.5] |
| 20%(기준) | 20 | 78.4 [65.9-86.3] | 84.5 [81.6-87.8] | 86.4 [84.6-89.0] |
| 20%(기준) | 50 | 83.8 [81.6-87.9] | 87.4 [85.3-90.0] | 90.9 [90.1-92.3] |

대기 ticket 만료로 잃은 구매자 수:

| 시작 후 포기율 | K | TTL 30s | TTL 120s | TTL 300s |
|---|---|---|---|---|
| 0% | 5 | 283.7 [282-286] | 255.3 [252-258] | 216.7 [203-228] |
| 0% | 20 | 231.0 [230-232] | 80.3 [77-85] | 38.0 [35-42] |
| 0% | 50 | 67.7 [64-72] | 3.0 [0-5] | 0.0 [0-0] |
| 5% | 5 | 284.0 [282-286] | 259.7 [252-264] | 226.7 [203-242] |
| 5% | 20 | 235.7 [232-242] | 95.7 [85-115] | 51.0 [42-60] |
| 5% | 50 | 71.3 [68-74] | 4.3 [0-9] | 0.0 [0-0] |
| 20%(기준) | 5 | 287.3 [284-289] | 283.3 [278-288] | 266.7 [256-276] |
| 20%(기준) | 20 | 251.7 [249-253] | 214.7 [176-240] | 104.0 [88-124] |
| 20%(기준) | 50 | 86.7 [82-89] | 23.0 [17-30] | 0.0 [0-0] |

품절을 알게 된 구매자 수:

| 시작 후 포기율 | K | TTL 30s | TTL 120s | TTL 300s |
|---|---|---|---|---|
| 0% | 5 | 0.0 [0-0] | 0.0 [0-0] | 16.3 [4-31] |
| 0% | 20 | 3.7 [0-6] | 137.3 [129-142] | 175.7 [168-181] |
| 0% | 50 | 141.3 [128-153] | 205.3 [199-212] | 208.3 [203-212] |
| 5% | 5 | 0.0 [0-0] | 0.0 [0-0] | 10.3 [0-31] |
| 5% | 20 | 1.3 [0-4] | 123.0 [107-133] | 162.3 [155-168] |
| 5% | 50 | 137.3 [128-142] | 204.0 [199-212] | 208.3 [203-212] |
| 20%(기준) | 5 | 0.0 [0-0] | 0.0 [0-0] | 0.0 [0-0] |
| 20%(기준) | 20 | 0.0 [0-0] | 19.0 [0-51] | 102.7 [83-118] |
| 20%(기준) | 50 | 126.0 [114-137] | 188.3 [185-191] | 208.3 [203-212] |

### Q-003 가설 판정 (이 모형 범위 안에서만)

| 가설 | 판정 | 근거 |
|---|---|---|
| H1 K↑ → 접수↑, 한계에서 정지 | **부분 지지** | K 5→10→20→50→100에서 접수 평균은 TTL 30초 기준 9.4 → 18.4 → 36.4 → 47.4 → 48.0, TTL 120초 기준 13.0 → 33.0 → 46.2 → 47.6 → 48.0. K 50 이상에서 정지한다. 정지 값 47.6~48.0은 재고 60에서 시작 후 포기 예약(평균 12.0~12.4)을 뺀 값이다. 재고가 아니라 **시작 후 포기가 묶은 예약**이 한계다. |
| H2 TTL↑ → 대기 만료↓, 접수↑, 낭비↑ | **부분 지지** | K=20에서 대기 만료로 잃은 구매자는 TTL 30 → 60 → 120 → 300초에 249.2 → 226.0 → 181.8 → 97.8명, 접수는 36.4 → 46.2 → 46.2 → 56.2. K 50 이상에서는 TTL 60초부터 접수가 변하지 않는다. 낭비는 늘었다: K=50에서 시작 전 포기가 slot 시간에서 차지하는 비율이 TTL 30 → 60 → 120 → 300초에 5.0% → 11.0% → 21.5% → 41.7%. |
| H3 불변식 0 | **지지** | 위 불변식 표. oversell은 DB 제약이 막는 값이라 정보량이 적고, K 초과와 순번 위반이 0인 것이 이 실험의 정보다. |
| H4 낭비의 대부분은 시작 후 포기 | **조건부 지지** | 시작 후 포기율을 20% → 0%로 낮추면 K=5·TTL 30초의 낭비가 75.8% → 3.8%, K=20·TTL 30초가 78.4% → 11.4%로 거의 사라진다(주 격자에서 TTL 30초일 때 시작 후 포기가 slot 시간의 53~76%. 이 비교의 20% 행은 시드 1~3 기준). 그러나 TTL 300초이고 K 50 이상이면 시작 전 포기가 붙잡는 slot 시간(41.7~42.2%)이 시작 후 포기(33.9~34.5%)보다 크다. 포기율을 0%로 줄여도 K=50·TTL 300초의 낭비는 81.4%로 남는다. 낭비의 원인은 TTL 구간에 따라 바뀐다. |

### Q-003 관찰 (측정된 것과 해석을 구분해 적음)

1. **접수 주문 수만 보면 K를 낮추는 쪽이 더 팔린다(측정). 그러나 비용이 따른다(측정).** K=20·TTL 300초는 접수 56.2로 K=100의 48.0보다 많다. 이유는 시작 후 포기 예약 약 12개가 10분 뒤 재고로 돌아올 때, 느린 대기열에는 아직 대기 중인 구매자가 있고 빠른 대기열은 모두 품절 안내를 받고 이미 떠났기 때문이다(해석: 이 모형은 품절 구매자가 떠난다고 가정한다). 시작 후 포기율이 0%이면 K=20·TTL 120초 이상에서 60/60이 다 팔려 이 효과가 없다. 비용은 대기 p95 686 가상초(K=20, TTL 300초)와 대기 만료로 잃은 구매자 평균 97.8명이다. K=100은 이 구매자를 잃지 않고 품절로 안내한다(`sold_out` 210.2명).
2. **TTL은 한 값이 두 일을 한다(코드 + 측정).** 대기 한도로서는 클수록, slot을 받고 시작하기까지의 한도로서는 작을수록 낫다. K=50에서 TTL을 30 → 300초로 늘리면 대기 만료는 87.6 → 0명이지만 시작 전 포기가 slot을 붙잡는 비율이 5.0% → 41.7%가 된다. 두 한도를 따로 정할 수 없는 것이 이 구조의 제약이다. 다만 분리가 이득인지는 이 실험이 보여 주지 못했다(분리한 변형을 돌리지 않았다).
3. **시작 후 slot과 재고는 TTL이 풀지 않는다(코드 읽기 + 측정).** 시작 후 포기율 20%일 때 slot 시간의 34~76%가 10분 window 만료로 끝났다. window 길이를 바꾸는 실험과 포기 시 즉시 반환하는 경로는 측정하지 않았다.
4. **느린 시작자를 잃는 TTL 하한(측정, 모형 값 의존).** T1 p95가 약 27초인 모형에서 TTL 30초는 `lost_slow_start`가 K 50 이상에서 7.0~10.0명(K 5~20: 0.4~2.2명), TTL 60초는 0~0.2명, 120초 이상은 0명이다. 구매자 시작 시간 분포의 p99 정도는 TTL이 넘어야 한다는 방향만 보인다. 실제 분포가 없어 값은 정하지 않는다.
5. **K × TTL이 대기 손실을 정한다(측정).** `lost_queue_expired`가 0이 되는 칸은 K=50·TTL 300초와 K=100·TTL 60초 이상뿐이고, K=5는 TTL 300초에서도 평균 263.2/300명을 잃는다. 이 모형에서 K=5~10은 도착 속도(30초 안에 300명)를 받지 못한다.
6. **순번.** 순번 위반 0과 재진입 ticket(구매자당 최대 2회)이 전역 대기열 끝에 붙는 동작이 함께 확인됐다(재진입 ticket을 포함한 `tickets` 표의 평균 300~645).

## Q-004: PayPal AUTHORIZE 사실과 repo 창

### 출처 (2026-11-25에 실제로 연 것만)

| 약칭 | 출처 | 연 방법과 상태 |
|---|---|---|
| [P1] | [Authorize a payment and capture funds later](https://developer.paypal.com/docs/checkout/standard/customize/authorization/) | HTTP 200, 본문 확인. |
| [P2] | PayPal Payments API v2 OpenAPI 명세 v2.12([paypal-rest-api-specifications `payments_payment_v2.json`](https://github.com/paypal/paypal-rest-api-specifications/blob/main/openapi/payments_payment_v2.json)) | 원본 JSON을 받아 `reauthorize`·`void`·`authorization` 정의와 예시를 읽음. |
| [P3] | PayPal Orders API v2 OpenAPI 명세 v2.32([`checkout_orders_v2.json`](https://github.com/paypal/paypal-rest-api-specifications/blob/main/openapi/checkout_orders_v2.json)) | 원본 JSON에서 `authorization.expiration_time` 정의를 읽음. |

**열었지만 본문이 없던 것(not confirmed):** developer.paypal.com의 API 레퍼런스 페이지(`/docs/api/payments/v2/`, `/docs/api/orders/v2/`, `/api/orders/v2/definitions/authorization_status`)와 [P1]이 링크한 honor period 안내(`/docs/checkout/advanced/authorization-honor/`)는 HTTP 200이지만 JavaScript 껍데기만 와서 내용을 읽지 못했다. 이 페이지들의 문장은 인용하지 않았고, 같은 내용은 [P1], [P2]에서 확인한 것만 적는다.

### 문서가 말하는 것

| 사실 | 출처 |
|---|---|
| authorization은 자금을 잡아 두고 **29일** 유효하다. | [P1] "An authorization places a hold on the funds and is valid for 29 days." |
| 성공한 authorization 뒤 **3일 honor period** 안에 capture해야 한다. | [P1] "capture the payment within the 3-day honor period." |
| 응답의 `expiration_time`은 "authorized payment가 만료되는 날짜와 시각"(RFC 3339)이다. 샘플 응답에도 `expiration_time`과 `void`, `reauthorize` 링크가 있다. | [P2], [P3] `authorization` 정의, [P1] 샘플 |
| 3일 안에 capture하지 않았다면 reauthorize로 자금 확보를 다시 확인할 수 있다. 29일 안에서 honor period가 끝난 뒤 여러 번 가능하고, reauthorize는 **새 authorization ID**를 만들며 3일 honor period를 다시 시작한다. 27일째 reauthorize하면 honor는 2일뿐이다. | [P1] |
| 원 authorization 후 30일이 지나면 reauthorize가 아니라 새 authorization을 만들어야 한다. | [P2] reauthorize 설명 |
| honor period 안의 reauthorize는 422 "Re-authorization is not allowed within the three-day honor period." | [P2] 예시 `authorizations_reauthorize_422_inside_honor_period` |
| 만료된 authorization의 capture는 422 "The authorization has expired." 예시가 있다. | [P2] 예시 `authorizations_capture_422_expired` |
| void는 authorization을 취소한다. 완전히 capture된 authorization은 void할 수 없다. | [P2] void 설명 |
| `authorization.status` 열거값은 CREATED, CAPTURED, DENIED, PARTIALLY_CAPTURED, VOIDED, PENDING이다. **EXPIRED가 없다.** 대신 "authorization이 만료로 void됨" webhook 알림이 정의돼 있다. | [P2] `authorization_status`, webhook `void-expired` |

### Sandbox에서 관찰한 것

`load-tests/paypal-real-fault/authorization-window.mjs`(Sandbox 전용, `api-m.sandbox.paypal.com`만 호출, 자격 증명은 `.env.local`에서 읽고 출력하지 않음). 흐름: access token → `POST /v2/checkout/orders`(intent AUTHORIZE, USD 1.00) → 이 repo가 이미 쓰는 방식대로 headless 브라우저가 Sandbox buyer 계정으로 승인(사람 개입 없음) → `POST /v2/checkout/orders/{id}/authorize` → 조회, reauthorize, void, 재 void, void 후 capture. 저장한 실행 n=3(앞서 같은 흐름으로 돌린 3회에서도 `expiration_time`이 같았지만 그 원자료는 저장하지 않았다).

| 관찰 | 결과 (n=3, 모두 같음) |
|---|---|
| authorize 응답 | HTTP 201, order `COMPLETED`, authorization `CREATED` |
| `expiration_time − create_time` | **696.00시간 = 29일**(세 번 모두. 예: 생성 `2026-11-25T01:19:22Z`, 만료 `2026-12-24T01:19:22Z`) |
| authorization 필드 | `amount`, `create_time`, `custom_id`, `expiration_time`, `id`, `links`, `seller_protection`, `status`, `update_time`. **honor period 필드는 없다.** `links` rel은 capture, reauthorize, self, up, void |
| `GET /v2/payments/authorizations/{id}` | 같은 `expiration_time`, 상태 `CREATED` |
| 생성 직후 reauthorize | 422 `REAUTHORIZATION_TOO_SOON` ([P2]의 "inside honor period"와 같은 상황) |
| void | 204. 이어서 조회하면 `VOIDED` |
| 같은 `PayPal-Request-Id`로 void 재전송 | 204 (재생) |
| **새** `PayPal-Request-Id`로 void 재전송 | 422 `PREVIOUSLY_VOIDED` |
| void 뒤 capture | 422 `AUTHORIZATION_VOIDED` |

**관찰하지 못한 것(not confirmed):** honor period가 끝난 뒤 capture의 결과(3일 대기 필요), 3일 이후 reauthorize 성공 경로, 29일 뒤 만료된 authorization의 `status`와 webhook(29일 대기 필요), Sandbox의 승인 전 order 유효 기간. [P1]·[P2]가 말하는 값을 Sandbox가 29일 만료로 보여 주는 것까지만 확인했다.

### repo가 쓰는 창 (코드와 설정 읽기)

| 창 | 기본값 | 시작점 → 끝점 | 위치 |
|---|---|---|---|
| ticket TTL | 기본 없음(Campaign 요청 값) | join 또는 slot 획득 → 대기 한도 또는 시작 한도 | `CampaignServiceImpl` |
| checkout window | `PT10M` | `startCheckout`(재고 예약) → 첫 AUTHORIZE 제출. 못 하면 만료·재고 반환·slot 반환(D-029, D-030) | `application.yml:105`, `OrderServiceImpl` |
| approval TTL | `PT30M` | AUTHORIZATION 단계 진입(`AUTHORIZATION_PENDING`, PayPal order 생성 요청 직전에 기록) → 구매자 승인 deadline. deadline 뒤에는 void하지 않고 provider 상태를 먼저 조회([ADR-0047](../02-decisions/adr/ADR-0047-abandoned-approval-deadline.md)) | `application.yml:130`, `PaymentServiceImpl.reservePhase`, `resolveExpiredApproval` |
| recovery window | `max-age PT24H`, `max-attempts 8`, `max-backoff PT15M` | 복구 job의 `retry_window_started_at` → 초과하면 `NEEDS_REVIEW`(운영자) | `application.yml:139-148`, `PaymentRecoveryRepository.expireExhausted` |

PayPal authorization은 구매자 승인 **뒤에** repo가 `/authorize`를 호출해야 생긴다(`resolveExpiredApproval`의 주석). 그래서 10분·30분 창은 authorization 유효 기간을 소모하지 않는다. authorization이 생긴 뒤에는 `continueCapture`로 같은 흐름 안에서 capture를 이어 가고, 결과를 모를 때(UNKNOWN)만 복구 worker가 같은 attempt로 대조한다. repo 코드는 `expiration_time`을 읽지 않고, reauthorize를 구현하지 않으며, authorization 만료·void webhook(`PAYMENT.AUTHORIZATION.*`)을 처리하지 않는다(`PaymentWebhookProcessor`가 다루는 이벤트는 `PAYMENT.CAPTURE.COMPLETED`와 환불 이벤트뿐, `git grep`으로 확인).

### 여유 계산 (문서 값 3일 honor와 29일 유효 대비)

| 비교 | 값 | 근거 |
|---|---|---|
| 승인 전 창(10분 + 30분 = 최대 40분) | authorization이 아직 없으므로 honor를 쓰지 않음 | 코드 읽기 |
| authorize 뒤 capture까지 정상 경로 | 같은 요청 흐름 안(초 단위). 이 실험은 지연을 측정하지 않았다 | 코드 읽기, 측정 없음 |
| 결과 불명 시 무인 복구가 쓰는 최대 구간 | 복구 window 24시간(재시도 8회, 최대 backoff 15분) → 3일(72시간) honor 대비 **약 48시간 여유** | 설정 읽기. 단 window는 job의 `retry_window_started_at`부터이고 이 값이 `CURRENT_TIMESTAMP`로 갱신되는 곳이 여러 군데라 **authorization 생성 후 절대 상한이라고 증명하지 못했다** |
| 24시간 뒤 | `NEEDS_REVIEW`. 운영자가 처리해야 하고, repo에는 reauthorize 경로가 없다. 3일을 넘기면 capture가 honor 보장 밖일 수 있다 | [P1]("capture within the 3-day honor period"), repo는 운영자 처리 시한을 코드로 강제하지 않음 |
| 만료·void된 authorization에 대한 repo 동작 | `voidAuthorization`은 모든 `RestClientException`을 `PayPalUnavailableException`(결과 불명)으로 감싼다. 같은 Request-Id 재전송은 204(재생)라 안전하지만, 새 Request-Id로 void하면 422 `PREVIOUSLY_VOIDED`다. | `PayPalRestClient.voidAuthorization`, 위 Sandbox 관찰. repo는 void에 안정적인 요청 ID를 쓰므로(`stableRequestId`) 재시도는 같은 ID로 나간다. 요청 ID를 잃은 경우의 경로는 실행하지 않았다 |

**margin 해석:** repo가 소비하는 시간은 "승인 전 40분(honor 밖)"과 "authorize 뒤 초 단위 + 불명 시 최대 24시간 복구 window"다. honor 3일과 비교하면 **정상 경로는 3일에 비해 무시할 수 있고 복구 경로도 24시간으로 3일의 3분의 1**이다. 이 값이 충분한지는 운영자 대응 시간(`NEEDS_REVIEW` 이후)에 달려 있고, 그 시간은 측정하지 못했다.

### Q-004 가설 판정

- **H5 지지(문서 + 코드 읽기).** authorization은 문서상 29일 유효·3일 honor다. repo의 10분·30분은 authorization이 생기기 **전** 창이라 honor를 쓰지 않는다. authorize 뒤 정상 경로는 같은 흐름 안의 capture이고, 결과 불명일 때의 복구 window는 24시간이다.
- **H6 지지(Sandbox n=3, 앞선 3회 포함 6회).** `expiration_time = create_time + 696.00시간(29일)`이고 honor period는 응답 필드에 없다. 3일 honor는 [P1]·[P2] 문서 값이며 API 응답으로 확인되지 않는다.
- **Q-004가 열려 있는 이유는 창이 짧아서가 아니라 값이 없어서다.** D-030이 요구한 p50/p95/p99(확인된 hold부터 첫 AUTHORIZE 제출까지), 만료·중단율, hold 점유는 실제 buyer가 없어 측정하지 못했다. 이 실험의 구매자 모형(T1, T2)은 그 분포가 아니라 가정이다.
- **repo의 빈 곳(코드 읽기, 실행하지 않음):** (1) `authorization.expiration_time`을 어디에도 저장·조회하지 않는다. 승인 후 capture가 막혀 `NEEDS_REVIEW`로 간 attempt가 honor 3일을 넘겼는지 알려 주는 필드가 없다. (2) reauthorize 경로가 없다. (3) authorization 만료·void webhook을 처리하지 않는다. 이 셋이 지금 실제 손실을 만든다고 보여 주는 측정은 없다. 정상 경로는 초 단위로 capture하고 복구는 24시간 window라 발생 조건이 드물다는 것이 코드 읽기의 판단이다.
- **코드는 바꾸지 않았다.** `expiration_time` 저장이나 `NEEDS_REVIEW` 경과 시간 알림은 후속 후보로만 남긴다. 값을 바꾸거나 새 필드를 넣을 만큼 근거(발생 빈도, 운영자 처리 시간)가 없다.

## 해석

- **값을 정하지 않은 이유.** K와 TTL이 결과를 크게 움직이는 구간은 구매자 시작 시간(T1), 제출 시간(T2), 시작 전·후 포기율, 품절 뒤 구매자 행동에 의존한다. 이 값들은 이 repo에 실측이 없고, 위 표에서 시작 후 포기율 하나만 20% → 0%로 바꿔도 같은 (K, TTL)의 접수가 45.3~45.7에서 60.0으로 바뀐다(K=50). 가정을 바꾸면 순위가 바뀌므로 하나를 고르면 가정을 값으로 굳히는 것이다. 그래서 기본값과 코드는 바꾸지 않는다.
- **이 harness가 주는 것.** 값을 정할 입력(구매자 시간 분포, 포기율)이 생기면 같은 코드로 다시 잴 수 있다. 코드는 입력 대부분을 환경 변수로 받는다(`SWEEP_*`). 구매자 시간 분포 상수는 소스에 있고 값 변경은 소스 수정이다.
- **바로 쓸 수 있는 설계 정보 세 가지.** (1) 불변식(순번, K 한도, 재고)은 K·TTL을 어떻게 골라도 이 모형에서 깨지지 않는다. 값은 안전이 아니라 손실 구성(대기 만료 대 품절 안내 대 포기)을 정한다. (2) TTL은 대기 한도와 시작 한도를 겸해 두 값을 따로 맞출 수 없다. (3) `startCheckout` 이후의 slot과 재고는 TTL이 아니라 10분 window가 잡고 있다.
- **재현 확인.** 주 격자 100회 전체를 두 번 독립 실행했고(두 번째는 slot 시간을 원인별로 나누는 지표를 추가한 harness), 비교한 지표 15종 × 100칸 = 1,500개 값이 모두 같았다(슬롯 초 합계만 부동소수 합산 순서로 6칸에서 0.01초 차이). 시드가 같으면 구매자 난수열이 같아 결정적이다. 이것은 n=5의 **시드 사이 변동**만 보여 주며 실제 구매자 변동은 아니다.

## 한계와 확인하지 못한 것

- **구매자 모형은 전부 가정이다.** N, S, 도착, T1, T2, 시작 전·후 포기율, 재진입, polling 주기, 품절 구매자의 이탈이 실측이 아니다. 시작 후 포기율만 민감도로 확인했고(0%, 5%, 20%), 나머지 가정의 민감도는 측정하지 않았다. 특히 품절 구매자가 떠난다는 가정과 재진입 확률(70%, 최대 2회)이 접수 수를 직접 바꾼다.
- **단일 Seller-SKU, 단일 스레드.** 동시 요청, 락 대기, 교착, 다중 인스턴스는 이 실험이 건드리지 않는다. Q-003의 "lock timeout/deadlock 복구"는 여전히 미정이다. 이 결과는 처리량이나 지연 결론이 아니다.
- **가상 시계.** 시간 값(대기 p50·p95, 마지막 접수 시각)은 모형이 정한 가상 초이고 서버 지연을 포함하지 않는다. 서비스 호출이 비용을 갖는 만큼의 시간은 가상 시계에서 0이다. expiry sweep의 `time-budget`(2초)만 실제 시계를 쓴다.
- **PayPal은 로컬 대역이다.** `submitPayment`는 승인 대기(approval URL 발급)까지만 가고 구매자 승인·capture는 없다. 이 모형의 "접수"는 첫 AUTHORIZE 제출이지 결제 완료가 아니다. 접수 뒤 승인 지연, 승인 포기, 복구는 범위 밖이다.
- **표본.** 칸당 n=5(민감도 n=3)이고 범위는 최소-최대다. 신뢰 구간이 아니며 시드가 같은 칸끼리는 짝 비교다. 칸 사이의 작은 차이(예: 47.4와 48.0)는 해석하지 않는다.
- **bot·인증·정책 값.** 측정하지 않았다(범위 밖 절).
- **Sandbox 관찰의 한계.** 3일, 29일 뒤의 동작, 승인 전 order 유효 기간, 같은 `PayPal-Request-Id` 재생의 보존 기간은 확인하지 못했다(not confirmed). PayPal 문서 페이지 중 JavaScript 껍데기만 받은 것은 인용하지 않았다. 문서 내용은 시점이 지나면 바뀔 수 있다(2026-11-25 기준).
- **repo의 복구 window 24시간이 authorization 생성 후 절대 상한인지는 증명하지 못했다.** `retry_window_started_at`이 여러 곳에서 갱신된다.
- **ADR을 쓰지 않았다.** 값이나 정책을 정하지 않았고 결정 로그 D-145와 이 문서로 충분하다고 판단했다.

## 재현

아래 명령의 `load-tests/admission-sweep/`와 `authorization-window.mjs`는 삭제했다. 실행하려면 먼저 커밋 `546d8002` 이후 이력에서 복원한다(동작은 이 문서 끝 "삭제한 파일의 내용" 절).

```sh
export JAVA_HOME=<JDK 25> DOCKER_HOST=<docker socket> TESTCONTAINERS_RYUK_DISABLED=true
# Q-003 주 격자(약 16분, 공유 머신 기준)
SWEEP_KS=5,10,20,50,100 SWEEP_TTLS=30,60,120,300 SWEEP_SEEDS=1,2,3,4,5 \
  load-tests/admission-sweep/run.sh /tmp/atlas-c2-results/main.jsonl
# 민감도
SWEEP_ABANDON_AFTER=0 SWEEP_KS=5,20,50 SWEEP_TTLS=30,120,300 SWEEP_SEEDS=1,2,3 \
  load-tests/admission-sweep/run.sh /tmp/atlas-c2-results/sens_a2_0.jsonl
python3 load-tests/admission-sweep/aggregate.py /tmp/atlas-c2-results/main.jsonl
# Q-004 Sandbox (.env.local 필요, 별도 worktree는 ENV_FILE로 지정)
cd load-tests/paypal-real-fault && npm ci
REAL_SANDBOX_OPT_IN=YES REPEAT=3 node authorization-window.mjs
```

## 삭제한 파일의 내용

이 절은 저장소에서 지운 하네스의 동작을 문서에 직접 옮긴 것이다. 위 본문과 구분하려고 따로 둔다.

### admission-sweep(`AdmissionKTtlSweepExperiment.java`, `run.sh`, `aggregate.py`)

- 구성: 실제 `AdmissionService`, `OrderService`(재고 예약과 결제 제출), PostgreSQL 18.6(Testcontainers, 라벨 `atlasexp=c2`)을 쓰고 시간만 가상 시계로 돌린다. 시드가 같으면 모든 (K, TTL) 칸이 같은 난수열을 써서 칸끼리 짝 비교가 된다.
- 실행: `run.sh`가 하네스를 `atlas-application/src/test/java/com/atlas/experiment/`에 임시 복사하고 `./gradlew --no-daemon --no-build-cache :atlas-application:cleanTest :atlas-application:test --tests 'com.atlas.experiment.AdmissionKTtlSweepExperiment'`를 실행한 뒤 반드시 복사본을 지운다(일반 `check`에 들어가지 않는다). 출력은 칸당 JSON 한 줄(jsonl).
- 환경 변수: `SWEEP_KS`, `SWEEP_TTLS`, `SWEEP_SEEDS`, `SWEEP_BUYERS`, `SWEEP_STOCK`, `SWEEP_ARRIVAL_SECONDS`, `SWEEP_ABANDON_BEFORE`(기본 0.10), `SWEEP_ABANDON_AFTER`(기본 0.20), `SWEEP_OUT`.
- 구매자 모형 상수: 재참여 확률 0.70, 최대 재참여 2회, 시작 전 사고 시간 중앙값 10초, 결제 전 사고 시간 중앙값 30초(로그정규 σ=0.6), 대기열 확인 주기 2초, 틱 1초, 주문 창 600초, 관측 한계 45분. 시작하려던 구매자가 START 이벤트에서 실제 서비스 오류를 만나면 `LOST_SLOW_START`로 센다.
- 만료 스케줄러는 끈다: `atlas.admission.expiry-poll-interval`, `atlas.orders.expiry-poll-interval`을 `PT24H`로, 결제 복구 worker와 웹훅 재시도 worker는 `false`로 둔다.

### authorization-window(`load-tests/paypal-real-fault/authorization-window.mjs`)

- Sandbox 전용(`api-m.sandbox.paypal.com`만 호출), 자격 증명은 `.env.local`에서 읽고 출력하지 않는다. 실행: `REAL_SANDBOX_OPT_IN=YES REPEAT=3 node authorization-window.mjs`. 흐름은 본문 "Sandbox에서 관찰한 것" 절의 설명과 같다. 결과 파일은 저장하지 않고 본문 표가 정본이다.
