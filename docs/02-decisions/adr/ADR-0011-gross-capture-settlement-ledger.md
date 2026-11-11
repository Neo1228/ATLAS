---
id: ADR-0011
title: 총액 캡처 정산 원장
status: Accepted
owner: Project Owner
date: 2026-11-04
links:
  decisions: [D-066, D-071]
  requirements: [Q-006]
---
# ADR-0011: 총액 캡처 정산 원장

- 상태: ✓ 확정
- 날짜: 2026-11-04
- 결정 ID: [D-066, D-071](../../00-governance/decision-log.md)
- 관련 요구사항: Q-006
- 관련 Issue: [Issue #115](https://github.com/Neo1228/ATLAS/issues/115)

## 배경

초기 원장 설계는 `PaymentAttempt`의 AUTHORIZE/CAPTURE/VOID operation 결과를 재조정하는 방식. 현재 provider 연동은 PayPal Sandbox이며 [ADR-0026](ADR-0026-paypal-sandbox-payments.md)에 정의.
Internal ledger는 확인된 capture/refund를 기록. Provider/bank statement reconciliation, fee/reserve와 회계 정정 정책은 [Q-006](../../01-requirements/requirements-index.md#assumptions-and-open-questions)에서 관리.

## 결정

- `CAPTURED`가 확인된 각 PaymentAttempt에 대해 append-only 이중기입 원장을 기록합니다.
- Campaign capture gross는 해당 OrderItem snapshot 합계. Cart checkout은 BuyerCheckout 전체 gross를 한 번 기록하고 Seller별 PurchaseOrder allocation 합계가 checkout total과 일치해야 함. 통화는 KRW.
- 차변은 `PSP_CLEARING`, 대변은 `SELLER_PAYABLE`이며 두 항목의 금액은 동일한 gross 금액입니다.
- 원장 원천 키는 `attemptId`. 같은 attempt·checkout·gross·Seller allocation facts replay는 no-op; 다른 payload 재사용은 conflict.
- Campaign Order 또는 Cart의 모든 Seller Order를 `PAID`로 전환하고 inventory를 claim하는 transaction에 journal·entries·allocations를 포함. PaymentAttempt의 `CAPTURED`는 PSP 호출 뒤 먼저 commit. 후속 실패 시 order·inventory·ledger rollback 후 같은 attempt로 reconciliation 재시도.
- `settlement-module`이 원장 계약·테이블을 소유하고 `order-module`이 확인된 capture facts를 전달합니다. 역방향 모듈 의존은 만들지 않습니다. Flyway migration은 `application-module`이 소유합니다.

## 현재 경계와 미결 정책

이 원장은 내부 capture receivable/payable 기록이지 PSP의 실제 정산 완료 증명은 아닙니다. 아래 항목은 별도 결정 전까지 구현하지 않습니다.

- PSP 거래 증빙의 수집·대조 및 unmatched/duplicate provider transaction 처리
- 수수료, 보류금, 순지급액, 지급 일정·실행, 실제 송금
- provider refund 실행, 회계 정정·reversal, claim 이후 재고 영향
- 여러 통화·Seller를 한 Order에 합치는 정책

## 근거와 대안

- 내부 capture 상태와 현재 `OrderItem` snapshot만 사용하면 외부 PSP 계약을 가장하지 않고 Q-006의 원장 영역을 독립적으로 검증할 수 있습니다.
- 단순 capture-event log는 계정 잔액을 표현하지 못하므로 owner가 선택한 이중기입 계약에 맞지 않아 제외했습니다.
- 수수료·보류·지급·환불까지 함께 계산하는 모델은 Q-006에서 정하지 않은 정책을 필요로 해 다음 단계로 미룹니다.

## 검증

- `attemptId` unique constraint와 transaction 단위의 두 entry 기록으로 재시도를 멱등하게 처리합니다.
- confirmed capture 하나에는 같은 gross 금액의 `PSP_CLEARING` debit과 `SELLER_PAYABLE` credit이 각각 하나 있어야 합니다.
- AUTHORIZED, PENDING/UNKNOWN, DECLINED, VOIDED에는 원장을 만들지 않습니다.
- ledger insert 실패 시 order PAID transition과 inventory claim이 함께 rollback되고, 동일 attempt 재조정으로 다시 적용할 수 있어야 합니다.
- 기존 payment attempt의 상태 의미, provider call 수, inventory conservation은 바꾸지 않습니다.

## 판단 근거

- [D-066](../../00-governance/decision-log.md), [Q-006](../../01-requirements/requirements-index.md#assumptions-and-open-questions), [Issue #115](https://github.com/Neo1228/ATLAS/issues/115)
- [ADR-0008: Catalog·Cart·Order 경계](ADR-0008-catalog-cart-order-boundaries.md)
- [D-056: UUIDv7 식별자](../../00-governance/decision-log.md)
