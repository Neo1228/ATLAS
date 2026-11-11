# ADR-0015 — 초기 payment-operation progression

> [!WARNING]
> 이 ADR은 초기 provider operation 저장 방식의 역사 기록. 현재 결제 흐름은 [ADR-0026 PayPal Sandbox](ADR-0026-paypal-sandbox-payments.md)와 [ADR-0029 recovery contract](ADR-0029-order-payment-recovery-contract.md) 기준.

| 항목 | 내용 |
|---|---|
| 상태 | 대체됨 (D-081) |
| 결정 ID | D-070 |
| 결정일 | 2026-11-05 |
| 관련 요구사항 | [FR-010](../../01-requirements/requirements-index.md) |
| 추적 | [Issue #127](https://github.com/Neo1228/ATLAS/issues/127) |

## 배경

초기 payment flow에서 provider operation 결과는 재시도와 order 후속 처리 실패 뒤에도 복구할 수 있어야 했습니다. 같은 operation 재전달이 외부 결과나 내부 side effect를 중복하지 않도록 operation state와 idempotency key를 저장하는 방식 검토.

## 초기 결정

- 당시 provider adapter는 PostgreSQL에 operation phase, `requestId`, 결과 상태를 저장.
- 같은 `(phase, requestId, idempotencyKey)` 재요청은 기존 결과 replay. 다음 scripted result는 새 key에서만 진행.
- 외부 operation result를 먼저 저장하고, Order·Inventory·Settlement·Fulfillment 변경은 별도 업무 transaction에서 수행.
- `reconcile`·`cancel`은 idempotent key로 보호. 입력 key 최대 길이 221자; 내부 suffix를 포함한 저장 컬럼은 `VARCHAR(255)`.
- 별도 polling worker는 도입하지 않고 명시적 reconciliation 사용.

## 근거와 결과

영속 operation state는 재시도 후 기존 결과를 복원하고, 외부 operation 결과와 내부 업무 효과의 transaction 경계를 분리합니다. 이 결정은 당시 provider adapter의 저장 방식만 설명하며 현재 PayPal provider contract를 정의하지 않음.

## 후속 결정

D-081에서 PayPal Sandbox authorization/capture/void/refund와 확인된 provider result 반영을 승인·구현. Current idempotency, webhook, timeout/unknown-result recovery는 [ADR-0026](ADR-0026-paypal-sandbox-payments.md)과 [ADR-0029](ADR-0029-order-payment-recovery-contract.md) 참조.

## 관련 자료

- [D-070 / D-081](../../00-governance/decision-log.md)
- [FR-002 / FR-010 / FR-017](../../01-requirements/requirements-index.md)
- [PR #129](https://github.com/Neo1228/ATLAS/pull/129), [PR #131](https://github.com/Neo1228/ATLAS/pull/131)
