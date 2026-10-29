---
id: DOC-014
title: Business Rules
status: Draft
owner: Project Owner
updated: 2026-10-29
links:
  requirements: [FR-001]
  adr: []
  experiments: [EXP-001]
---
# 비즈니스 규칙
Document type: Reference

이 문서는 Gate 1에서 확정/제안된 규칙과 미정 경계를 분리해 기록한다. 예시는 모두 합성 학습 시나리오다.

| ID | 규칙 | 상태 | 적용 범위 / 미정 경계 |
|---|---|---|---|
| BR-001 | 같은 논리적 대기열에서 구매 기회를 정할 때 서버가 발급한 순번을 사용하며 낮은 순번이 먼저다. | Confirmed baseline (D-019) | 자격 확인 후 queue admission에서 발급 (D-020); 같은 논리 요청 retry는 ticket/순번 재사용 (D-021); worker/service 재시작 뒤 순번 보존 (D-022). key 범위/보존기간, 변경 payload, 영속화 원자성, 만료/재입장, tie, bot fairness와 sale-type queue 경계는 [Q-003](../00-governance/assumptions-and-open-questions.md)에서 OPEN; baseline은 완료된 [Issue #24](https://github.com/Neo1228/ATLAS/issues/24)에 기록. |
| BR-002 | 판매자는 정해진 판매 가능량을 초과해 확정하지 않기를 원한다. | Proposed (D-017) | 판매 가능량의 단위, 캠페인/전역 재고, 입고·조정·취소·반품의 처리 방식은 Q-002 OPEN. |

## 기준 타임라인 — 합성 시나리오
1. 판매자가 제한된 판매 가능량을 가진 캠페인을 연다. 수량/상품 유형과 판매 정책은 아직 정하지 않는다.
2. 자격 확인을 통과한 요청을 queue에 admission할 때 서버가 대기열 순번을 발급한다. 발급과 queue state 저장의 원자성/정본 저장소는 OPEN.
3. 남은 capacity보다 요청이 많으면 순번과 재고/예약 결과가 일관되는지 검증한다. 예약/확정 처리의 세부 프로토콜은 설계 전에 정한다.
4. 같은 논리 요청 retry는 기존 ticket/순번을 재사용한다. worker/service 재시작 뒤 이미 발급된 순번은 유지한다. key retention, changed payload, 만료/취소/재입장 semantics는 OPEN.

이 타임라인은 정책 결론이나 성능 결과가 아니다. 관련 불변조건은 [Invariants](invariants.md), 탐색 후보는 [Experiment Registry](../08-experiments/experiment-registry.md)를 참조한다.
