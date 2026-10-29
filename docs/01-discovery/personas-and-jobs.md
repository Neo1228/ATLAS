---
id: DOC-013
title: Personas and Jobs to Be Done
status: Draft
owner: Project Owner
updated: 2026-10-29
links:
  requirements: [FR-001, NFR-002]
  adr: []
  experiments: [EXP-001, EXP-003]
---
# 가상 사용자 목표

## 근거와 한계
- ✓ 사용자가 선택한 Buyer/Seller 공통 목표.
- 실제 인터뷰·거래·시장 검증 결과 아님.
- 유형별 행동과 차이: ? OPEN (D-005, D-007, D-015~D-017; Q-001/Q-010).

## 공통 역할

| 역할 | 가상 목표 | 미정 사항 |
|---|---|---|
| Buyer | 공정한 구매 기회, 정확한 재고/예약 상태 | 자격 조건, 판매 유형별 흐름 (Q-002/Q-010) |
| Seller | 공정한 한정판매, overselling 방지 | 판매 단위, 재고 범위, 운영 차이 (Q-002/Q-003/Q-010) |

### Buyer job — Draft
한정 판매에서 공정한 기회와 정확한 예약 결과를 원함. 실제 사용자 발언 아님.

### Seller job — Draft
정한 판매 가능량 안에서 공정하게 판매하고 예약 결과를 확인하려는 가상 목표. 실제 판매자 발언 아님.

## 기준 시나리오
제한 재고에 여러 요청이 동시에 도달. 대기열 순서와 재고 예약 결과가 일치해야 하며 overselling 방지 필요 (D-010).

## 실패/경계 조건
- 자격 확인 후 대기열 입장·순번 발급.
- 같은 논리 요청 retry, service restart 후에도 순서 유지.
- 판매량 수식 `Available + Reserved + Sold = Initial Stock`은 입고/조정이 없는 단순 모델에만 적용. 승인된 invariant 아님.

## 판매 유형별 차이
수량/SKU, 좌석/티켓은 Q-002 논의 예시. 확정 목록이나 범위 제한 아님 (D-006). 실제 차이가 확인되면 별도 scenario 추가.

## 추적
- 대기열 기준 D-019~D-022: 자격 통과 후 admission 시 순번 발급, 같은 요청 retry는 기존 ticket 재사용, restart 후 순서 보존.
- 미정: 저장 원자성, key 범위/보존기간, 만료·재입장, 동순위, bot 방어, 유형별 차이 ([Q-003](../00-governance/assumptions-and-open-questions.md); [Issue #24](https://github.com/Neo1228/ATLAS/issues/24)).
- 연계: [Requirements](../02-requirements/requirements-index.md), [Business Rules](../03-domain/business-rules.md), [Invariants](../03-domain/invariants.md), [Experiments](../08-experiments/experiment-registry.md).
