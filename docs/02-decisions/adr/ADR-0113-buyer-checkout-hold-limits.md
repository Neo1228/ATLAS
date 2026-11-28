# ADR-0113: Buyer 한 명의 미결 checkout은 1건, 예약 수량 합은 50개로 제한하고 서버에서 cart 잠금 아래 확인한다

- 상태: Accepted(사용자 요청 "Buyer·cart당 미결 checkout 1건, 설정 가능한 Buyer 예약 수량 상한"에 따른 에이전트 결정. 기본값 50은 가정)
- 날짜: 2026-11-28
- 관련 결정: [ADR-0084](ADR-0084-checkout-ttl-authorize-slo-and-load-contract.md)(checkout window 10분), [ADR-0088](ADR-0088-admission-tombstone-purge-and-live-ticket-limit.md)(캠페인 대기열의 live ticket 상한)
- 근거: 이슈 [#739](https://github.com/Neo1228/ATLAS/issues/739), 회귀 시험 `CheckoutBuyerHoldLimitIntegrationTest`, `RemoteReservationCheckoutIntegrationTest.pendingRemoteCheckoutBlocksASecondCheckoutOfTheSameCartWithANewKey`

## 문제

Buyer checkout의 유일성은 `(buyer_ref, idempotency_key)`뿐이었다(`V1__baseline.sql` `buyer_checkout_key_unique`). 두 결함이 여기서 나왔다. 둘 다 수정 전 코드에서 시험으로 재현했다.

1. **원격 예약 모드의 중복 checkout.** 원격 예약은 workflow가 RESERVED가 될 때까지 cart를 비우지 않는다(`CheckoutFinalizationServiceImpl`). 예약 대기(RESERVATION_PENDING) 중 새 Idempotency-Key로 다시 checkout하면 같은 cart로 두 번째 checkout과 원격 예약이 생겼다. 두 checkout을 모두 결제하면 같은 물건을 두 번 산다.
2. **재고 독점.** cart 수량과 checkout에 상한이 없었다. 재고 5개인 SKU를 Buyer 한 명이 5개 담아 checkout하면 checkout window(10분) 동안 다른 Buyer는 재고 없음을 받고, 만료 뒤 같은 일을 되풀이할 수 있었다. ONLINE_STORE 채널에는 캠페인 대기열(ADR-0088의 live ticket 상한)과 달리 Buyer별 제한이 없다.

## 결정

1. **미결 checkout 상한(`atlas.checkout-buyer-limit.open-checkouts`, 기본 1).** 재고를 잡고 있는 checkout을 미결로 센다. RESERVATION_PENDING, PAYMENT_PENDING, PAYMENT_UNKNOWN, INVENTORY_CLAIM_PENDING, CANCEL_PENDING은 늘 세고, RESERVED는 child order의 checkout window가 남은 것만 센다(지난 것은 만료 sweep 전이라도 결제할 수 없다). 상한에 닿으면 409 `CONFLICT`로 거절한다. Buyer는 열린 checkout을 결제하거나 취소하면 다시 checkout할 수 있다.
2. **예약 수량 상한(`atlas.checkout-buyer-limit.reserved-units`, 기본 50).** 미결 checkout들이 예약한 수량 합과 이번 cart 수량의 합이 상한을 넘으면 400 `INVALID_REQUEST`로 거절한다. 거절은 재고·쿠폰·cart를 바꾸기 전에 한다.
3. **같은 Idempotency-Key 재시도는 상한보다 먼저 처리한다.** 기존 checkout을 돌려주는 분기가 상한 검사 앞에 있으므로 응답을 잃은 클라이언트의 재시도는 막히지 않는다.
4. **원자성은 Buyer cart 행 잠금에 기댄다.** 확인(`BuyerCheckoutRepository.findOpenHold`)과 생성이 `carts.lockForCheckout`의 `SELECT ... FOR UPDATE` 아래 같은 transaction에서 일어나므로 같은 Buyer의 cart checkout은 직렬화된다. 이 전제는 기대는 코드(`CartCheckoutServiceImpl.enforceBuyerHoldLimits`) 옆에 적었다. 스키마는 바꾸지 않는다.
5. **기본값은 `CartCheckoutServiceImpl` 생성자의 `@Value` 한 곳에 둔다.** 시험용 `application.yml`이 운영 파일을 가리므로 `atlas.checkout-window`처럼 코드 기본값을 쓴다. Compose는 Spring 환경 변수 규칙의 이름을 넘기며 같은 값을 적는다. 환경 변수 `ATLAS_CHECKOUT_BUYER_LIMIT_OPEN_CHECKOUTS`, `ATLAS_CHECKOUT_BUYER_LIMIT_RESERVED_UNITS`, `ATLAS_CHECKOUT_BUYER_LIMIT_EXPIRED_HOLD_COOLDOWN`으로 바꾼다. 한 Buyer로 결제하지 않은 checkout을 반복해 만드는 k6 하네스(`buyer-checkout.js`, `coupon-redemption.js`)는 운영 가이드대로 두 값을 올려서 돌린다.
6. **만료된 hold의 SKU 재checkout 쿨다운(`atlas.checkout-buyer-limit.expired-hold-cooldown`, 기본 30분).** 수량 합 상한(50)만으로는 재고 50개 이하 SKU를 한 Buyer가 통째로 잡고, window(10분)가 끝나면 다시 잡는 일을 끝없이 되풀이할 수 있었다. Buyer가 결제하지 않고 window를 넘긴 checkout(만료 sweep 전 RESERVED, EXPIRED)이 지난 30분 안에 만료됐고 이번 cart와 같은 SKU를 담았으면 409 `CONFLICT`로 거절한다. 그러면 한 Buyer가 같은 SKU를 잡는 시간은 40분 중 최대 10분이고, 그 사이 다른 Buyer가 살 수 있다. 회귀 시험 `buyerWhoLetAWholeStockHoldExpireCannotImmediatelyHoldItAgain`은 운영 기본값으로 돈다.

## 대안과 기각 이유

| 안 | 근거 | 결정 |
|---|---|---|
| 부분 unique 인덱스 `buyer_checkout(buyer_ref) WHERE state IN (...)` | RESERVED의 만료 여부는 child order의 시각이라 인덱스 조건에 넣을 수 없다. 만료 sweep 전의 RESERVED가 Buyer를 막는다. 기존 행에 중복이 있으면 migration이 실패하고, 상한을 설정으로 바꿀 수 없다 | ✗ 기각 |
| RESERVATION_PENDING만 막음(결함 1의 최소 수정) | 결함 2(독점)를 막지 못한다 | ✗ 기각 |
| cart 수량 상한(cart 담기에서 거절) | 예약은 checkout에서 일어나므로 cart만 막으면 여러 checkout의 합을 막지 못한다. checkout 상한이 같은 수량을 서버에서 지킨다 | ✗ 기각 |
| SKU별 수량 상한 | 상품마다 값이 필요해 catalog 스키마와 Seller 설정이 늘어난다. 요청 범위(Buyer 예약 수량 상한)를 넘는다 | ✗ 기각 |
| checkout 요청 속도 제한 | 독점은 요청 수가 아니라 잡은 수량의 문제다. 속도 제한은 인프라(게이트웨이)의 몫이다 | ✗ 기각 |
| Buyer별 SKU 몫 상한(팔리지 않은 재고의 50%, 최소 1개) | 구현해 application·order 시험을 돌렸더니 재고 2~3개를 모두 사는 정상 구매 시험 14건이 400을 받았다. 재고가 적은 상품을 다 사는 것은 정상 구매다. 최소 1개라 재고 1개 SKU의 반복 독점도 막지 못한다 | ✗ 기각 |
| 위 1~6 채택 | 회귀 시험 3건이 수정 전 실패·수정 뒤 통과 | ✓ 채택 |

## 결과

- Buyer 한 명은 한 번에 checkout 하나만 열 수 있다. 결제하지 않고 떠난 checkout이 있으면 window(10분)가 끝나거나 취소할 때까지 새 checkout이 409를 받는다.
- 한 Buyer가 한 번에 잡는 재고는 기본 50개 이하다. 재고가 상한보다 적은 SKU는 여전히 한 Buyer가 모두 잡을 수 있다. 그런 SKU를 지키려면 상한을 낮추거나 캠페인 대기열(ADR-0088)을 쓴다. 결제하지 않고 만료시킨 Buyer는 같은 SKU를 30분 동안 다시 잡지 못한다(결정 6). 남은 노출: (1) 취소는 쿨다운에 넣지 않았다. 취소는 재고를 바로 돌려주므로 취소와 재checkout 사이에 다른 Buyer가 살 틈이 있지만, 자동화한 Buyer가 이를 빠르게 되풀이하면 사실상 계속 잡을 수 있다. (2) Buyer 식별자를 여럿 쓰면 식별자마다 따로 잡는다. 식별자 발급 통제는 이 결정 밖이다. 이런 SKU를 확실히 지키려면 캠페인 대기열(ADR-0088)을 쓴다.
- checkout마다 Buyer의 미결 checkout을 세는 질의가 한 번 더 돈다. `buyer_checkout_key_unique(buyer_ref, ...)`, `purchase_order_checkout_seller_unique(checkout_id, ...)`, `order_item_order_sku_unique(order_id, ...)` 인덱스를 탄다.

## 롤백

- 스키마 변경이 없다. 두 환경 변수를 크게 올리면 코드를 되돌리지 않고 검사를 사실상 끌 수 있다. 이전 jar로 다시 배포해도 된다. 되돌린 동안에는 위 두 결함이 다시 생긴다.

## 재검토 조건

- 409(미결 checkout) 거절이 하루 checkout 시도의 1%를 넘거나 "이전 checkout 때문에 주문할 수 없다"는 민원이 생기면: 미결 상한을 2 이상으로 올리거나 새 checkout이 이전 RESERVED checkout을 자동 취소하는 안을 잰다.
- 409(만료 쿨다운) 거절 민원이 생기면: 쿨다운을 줄이거나 `PT0S`로 끈다. 취소 반복 독점이 관측되면 취소도 쿨다운에 넣는다.
- 400(수량 상한) 거절이 대량 구매 Buyer에게서 나오면: 기본값 50을 올리거나 Buyer 등급별 상한을 잰다.
- cart를 거치지 않고 BuyerCheckout을 만드는 경로가 생기면: 결정 4의 잠금 전제가 깨지므로 그 경로도 같은 잠금을 잡게 하거나 DB 제약을 다시 잰다.
