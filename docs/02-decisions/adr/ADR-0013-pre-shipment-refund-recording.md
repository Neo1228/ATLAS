---
id: ADR-0013
title: 배송 전 로컬 환불 기록
status: Accepted
owner: Project Owner
date: 2026-11-04
links:
  decisions: [D-068]
  requirements: [FR-008, Q-002, Q-006]
---
# ADR-0013: 배송 전 로컬 환불 기록

- 상태: ✓ 확정
- 결정일: 2026-11-04
- 결정 ID: D-068
- 관련 요구사항: [FR-008/BR-008](../../01-requirements/requirements-index.md), [Q-002/Q-006](../../01-requirements/requirements-index.md#assumptions-and-open-questions)
- 관련 Issue: [Issue #122](https://github.com/Neo1228/ATLAS/issues/122)

## 배경

현재 capture 원장은 확인된 결제 총액을 내부적으로 기록하지만 실제 PSP 정산이나 판매자 송금을 증명하지 않습니다. 결제 완료 뒤 취소·환불 및 claim 재고 처리 정책은 미정이었습니다. 이 구현도 Fake PSP의 refund operation을 호출하지 않으며 외부 자금 이동을 수행하지 않습니다.

## 결정

- 단일 Seller `PAID` Order의 부분·전체 환불 기록을 OrderItem 수량 단위로 허용. 금액은 주문 시 저장한 KRW unit-price snapshot × 환불 수량으로 계산.
- 같은 OrderItem의 누적 기록 수량은 주문 수량 이하. 요청은 `Idempotency-Key`로 보호; 같은 key·facts 재요청은 기존 기록 반환, 다른 facts 재사용은 conflict.
- Fulfillment가 `UNFULFILLED` 또는 `IN_PROGRESS`인 경우만 허용. `SHIPPED` 이후는 거절.
- 각 refund record에 대해 원본 capture journal을 변경하지 않고 별도 reversal journal 생성: `SELLER_PAYABLE` debit, `PSP_CLEARING` credit. 두 entry는 동일한 환불 gross 금액.
- 해당 claimed reservation 수량만 available stock으로 복원. 환불 기록·inventory 복원·reversal journal·aggregate 상태는 하나의 PostgreSQL transaction.
- `OrderState.PAID`는 terminal 상태로 유지. 별도 refund-record status를 사용하며 수량 전체가 기록된 Order는 추가 fulfillment 전이 거절.
- API 응답은 `executionMode: LOCAL_LEDGER_ONLY`로 한정. 이는 내부 원장 기록이며 고객에게 돈이 반환됐다는 뜻이 아님.

## 비범위

- 실제 PSP refund 호출·거래 증빙 대조·현금 이동
- `SHIPPED` 이후 반품 접수·검수·재입고
- 세금·배송비 배분, 다중 통화, 다중 Seller 주문
- 회계 정정 및 capture 원장 재작성

## 선택 이유와 절충

- 수량 단위 요청은 금액만 받는 방식보다 환불 금액과 재고 복원 수량을 직접 연결.
- shipment 이후 환불을 미루면 물리적 반품·검수 없이 재판매 재고를 늘리지 않음.
- append-only reversal은 capture 근거를 보존하고 수정·재기록 이력을 남김.
- 실제 PSP와의 연결을 흉내내지 않고 내부 기록 범위를 명확히 제한. `LOCAL_LEDGER_ONLY` 응답은 실제 자금 반환 완료로 오인할 수 없도록 표현.

## 검증 기준

- 환불 금액은 OrderItem snapshot을 기준으로 정확히 계산; 누적 환불 수량 초과 거절.
- same-key replay는 refund·journal·inventory 증감을 중복 생성하지 않으며, 다른 facts는 conflict.
- `UNFULFILLED`/`IN_PROGRESS`에서만 가능; `SHIPPED` 이후 거절.
- refund 원장 entry는 동일 금액의 `SELLER_PAYABLE` debit과 `PSP_CLEARING` credit이며 원 capture entries는 불변.
- 처리 중 실패하면 refund record·reversal·stock restore가 모두 rollback.
- 완전 기록 후 fulfillment 진행을 차단하고 `OrderState.PAID` 유지.
- API가 `LOCAL_LEDGER_ONLY`를 반환하며 외부 결제 효과를 주장하지 않음.

## 근거

- [D-068](../../00-governance/decision-log.md), [FR-008/BR-008/Q-002/Q-006](../../01-requirements/requirements-index.md), [ADR-0011](ADR-0011-gross-capture-settlement-ledger.md)
- 정책은 소유자 결정. 외부 PSP 동작·반품 정책을 근거로 일반화하지 않음.
