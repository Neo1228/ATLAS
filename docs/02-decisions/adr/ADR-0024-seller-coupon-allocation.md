# ADR-0024: Seller coupon 할인 배분과 환불 snapshot

- 상태: ✓ 구현·JDK 25/local PostgreSQL Testcontainers full Gradle check 통과 (41 suites, 212 tests, 0 failures/errors/skips); `bootJar` 성공
- 결정일: 2026-11-09
- 관련 작업: [Issue #186](https://github.com/Neo1228/ATLAS/issues/186)
- 선행 결정: [ADR-0023](ADR-0023-seller-sku-time-bounded-sale-price.md)

## 배경

ADR-0023은 SKU별 sale price와 주문 단가 snapshot을 도입했지만 order-level coupon은 제외했습니다. Multi-seller checkout에 정액 coupon을 추가하면 Seller별 할인 귀속, line별 settlement, partial refund 금액이 하나의 계산 규칙을 공유해야 합니다. 주문 당시 금액을 다시 계산하지 않으면 coupon 변경 이후에도 환불·정산 금액이 안정적입니다.

## 결정

- Seller가 고정 금액·기간 한정 coupon code를 소유. Code는 Seller별 고유, 대소문자 무시 후 대문자로 저장.
- Coupon validity: `[startsAt, endsAt)`. 사용 횟수 제한·예약·차감 상태는 V1에서 제외.
- Multi-seller checkout에서 SellerOrder별 coupon 최대 1개. 해당 Seller 상품에만 적용.
- Coupon은 seller-funded 정액 할인. SellerOrder subtotal 미만이어야 하며 결제액 0원은 허용하지 않음.
- 하나라도 유효한 SKU sale price가 있는 SellerOrder에는 coupon 적용을 거절. Sale price와 coupon stacking 금지.
- Coupon amount를 line subtotal 비율로 배분. 정수 나눗셈 floor 후 잔여 원을 소수 잔여값 내림차순으로 배정하고 동률은 `skuId` 오름차순으로 결정.
- Order와 OrderItem에 coupon identity·code·총액·line 배분액 snapshot 저장. Capture·settlement는 순액 기준.
- Partial refund는 주문 당시 line 배분액과 기존 환불 수량으로 계산. 할인 잔여 원은 주문 수량 중 앞선 단위부터 배정. 각 단위 환불액은 0원보다 커야 하며, 이를 만족하지 못하는 coupon allocation은 거절. 이후 Catalog/Coupon 상태를 재조회하지 않음.
- 같은 idempotency key는 기존 주문 snapshot을 반환. 다른 coupon 선택은 conflict.

## 고려한 대안

| 안 | 장점 | 단점 | 결정 |
|---|---|---|---|
| V1에서 coupon 제외 | 금액 정산·환불 규칙 단순 | 플랫폼의 핵심 상거래 할인 흐름이 검증되지 않음 | 기각 |
| SKU sale price와 coupon stacking | 사용자 할인 선택 폭 확대 | 할인 순서·한도·재원 귀속·환불 재배분 규칙 증가 | 기각 |
| 사용 횟수·동시 redemption 관리 | 한정 수량 coupon 지원 | 예약·결제 실패 해제·동시성 제어 상태 추가 | 후속 검증 전 보류 |
| SellerOrder별 정액 coupon, 순액 line snapshot | Seller 귀속과 환불 금액을 주문 시 확정 | proportion allocation 로직과 snapshot 컬럼 필요 | 채택 |

## 근거와 절충

- Taobao 주문 금액 문서는 상품별 할인과 주문별 할인 금액을 구분합니다. 할인 유형을 구분하고 SellerOrder 경계를 지키는 기준으로 참고했습니다.
- Shopify의 할인 조합 규칙은 product/order/shipping discount를 독립 정책으로 다룹니다. 조합 정책을 명시하지 않은 채 stacking하지 않는 근거입니다.
- [Woshipm 분석 글](https://www.woshipm.com/operate/6414206.html)은 부분 환불과 coupon 사용 상태를 다룬 사용자 기고 자료입니다. 기술 표준이 아닌 보조 product 사례로만 사용했습니다.
- Proportional allocation은 line별 net amount 합계가 SellerOrder 결제액과 일치하고, partial refund가 원래 할인 배분을 보존하는 대가로 계산 코드와 snapshot 저장량을 늘립니다.
- [SettlementServiceImpl](../../../ATLAS-settlement/src/main/java/com/atlas/settlement/service/impl/SettlementServiceImpl.java)는 환불 posting 0원을 거절. Coupon allocation은 무료 item unit을 만들지 않음.
- V1은 사용 횟수 제한 없이 재사용 가능. Finite global cap은 [ADR-0025](ADR-0025-limited-coupon-redemption.md)에서 별도 redemption state machine과 함께 추가.

## 검증 기준

- 시간 경계, Seller ownership, 중복 code, coupon 금액 한도, stacking 거절.
- multi-seller 격리, 비례 배분 합계, tie-break 결정성, line별 순액 양수.
- 같은 checkout replay, 충돌하는 coupon replay, partial/full refund 금액.
- Seller settlement capture와 reversal 합계 일치.
- 로컬 PostgreSQL/Testcontainers 전체 테스트 통과. 측정 전 성능 개선 주장은 하지 않음.

## 참고자료

- [Taobao Open Platform: Order amount definitions](https://developer.alibaba.com/docs/doc.htm?articleId=108471&docType=1&treeId=796)
- [Shopify Help: Discount combinations](https://help.shopify.com/en/manual/discounts/discount-combinations)
- [Woshipm: Partial refund and coupon lifecycle analysis (user-contributed)](https://www.woshipm.com/operate/6414206.html)
