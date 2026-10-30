---
id: DOC-013
title: Personas and Jobs to Be Done
status: Draft
owner: Project Owner
updated: 2026-10-30
links:
  requirements: [FR-001, NFR-002]
  adr: []
  experiments: [EXP-001, EXP-003]
---
# 가상 사용자 목표

## 근거와 한계
- ✓ 사용자가 선택한 Buyer/Seller 공통 목표.
- 실제 인터뷰·거래·시장 검증 결과 아님.
- 초기 합성 시나리오: campaign별 독립 pool에 수량형 SKU 1종 ([D-023](../00-governance/decision-log.md)). 다른 판매 유형별 행동은 ? OPEN ([Q-002/Q-010](../00-governance/assumptions-and-open-questions.md)).

## 공통 역할

| 역할 | 가상 목표 | 미정 사항 |
|---|---|---|
| Buyer | 공정한 구매 기회, 정확한 재고/예약 상태 | 자격 조건, 판매 유형별 흐름 ([Q-002/Q-010](../00-governance/assumptions-and-open-questions.md)) |
| Seller | 공정한 한정판매, overselling 방지 | 판매 단위, 재고 범위, 운영 차이 ([Q-002/Q-003/Q-010](../00-governance/assumptions-and-open-questions.md)) |

### Buyer job — Draft
한정 판매에서 공정한 기회와 정확한 예약 결과를 원함. 실제 사용자 발언 아님.

### Seller job — Draft
정한 판매 가능량 안에서 공정하게 판매하고 예약 결과를 확인하려는 가상 목표. 실제 판매자 발언 아님.

## 기준 시나리오
초기 시나리오: 한 campaign의 단일 수량형 SKU/pool. Sale open 후 동시 요청을 받아 queue 순서와 예약량이 pool capacity를 넘지 않는지 검증 ([D-010/D-019/D-023/D-024](../00-governance/decision-log.md)). 사용자 조사 결과가 아닌 선택된 학습 시나리오 ([Business Rules](../03-domain/business-rules.md)).

## 실패/경계 조건
- Sale open 전 요청은 queue entry/sequence를 만들지 않음. Open 후 자격 통과 요청만 admission.
- 같은 논리 요청 retry는 ticket을 재사용하고, service restart 후 순서 유지 ([D-021/D-022](../00-governance/decision-log.md)).
- Reservation TTL, payment 경합, 재고 조정/취소, 다른 판매 유형은 ? OPEN. `Available + Reserved + Sold = Initial Stock`은 상태와 조정 규칙이 정해지기 전 승인된 invariant가 아님.

## 판매 유형별 차이
초기 시나리오는 수량형 SKU 1종. 좌석/티켓 등 다른 단위는 이번 시나리오에서 제외하되 장기 탐색 범위에서 제거하지 않음 ([D-006/D-023](../00-governance/decision-log.md); [Q-002/Q-010](../00-governance/assumptions-and-open-questions.md)).

## 추적
- 초기 queue 경계 [D-024](../00-governance/decision-log.md): sale open 전 admission 없음; open 후 자격 통과 요청은 [D-019](../00-governance/decision-log.md) FIFO. Retry ticket reuse/restart 순서 ([D-021/D-022](../00-governance/decision-log.md)).
- 미정: storage atomicity, key 범위/보존기간, payload 변경, expiry/re-entry, tie, bot 방어, 재고 조정/취소, 공유 재고, 다른 판매 유형 ([Q-002/Q-003/Q-010](../00-governance/assumptions-and-open-questions.md); [Issue #33](https://github.com/Neo1228/ATLAS/issues/33)).
- 연계: [Requirements](../02-requirements/requirements-index.md), [Business Rules](../03-domain/business-rules.md), [Invariants](../03-domain/invariants.md), [Experiments](../08-experiments/experiment-registry.md).
