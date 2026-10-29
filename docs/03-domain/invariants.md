---
id: DOC-015
title: Domain Invariants
status: Draft
owner: Project Owner
updated: 2026-10-29
links:
  requirements: [FR-001, NFR-002]
  adr: []
  experiments: [EXP-001, EXP-005]
---
# 도메인 불변조건
문서 유형: 기준 자료

불변조건은 정상 처리와 실패/복구 뒤에도 계속 참이어야 하는 성질이다. 수치, 저장 방식과 검증 계약은 Gate 2~4에서 정한다. 용량/저장 용어는 [Glossary](../01-discovery/glossary.md)를 참조한다.

| ID | 조건 | 상태 | 전제 / OPEN 경계 | 연결 |
|---|---|---|---|---|
| INV-001 | 한 판매 단위에서 확정한 예약·판매 수량은 그 단위의 판매 가능 수량(capacity)을 넘지 않는다. | Proposed; 판매자 목표에서 도출 | 판매 단위, 재입고·조정·취소·반품 방식, 여러 캠페인의 재고 공유는 [Q-002](../00-governance/assumptions-and-open-questions.md)에서 미정. 수량을 보장하는 정본 저장소와 원자성은 Gate 3 설계 대상. | [BR-002](business-rules.md), [FR-001](../02-requirements/requirements-index.md), [EXP-001/005](../08-experiments/experiment-registry.md) |
| INV-002 | 같은 대기열에서는 서버 순번이 작은 유효 요청이 먼저 기회를 얻는다. 자격 확인 뒤 입장할 때 순번을 발급하고, 같은 요청의 재시도는 기존 ticket을 사용하며, 서비스 재시작 뒤에도 발급 순서를 보존한다. | 기준은 Confirmed (D-019~D-022); 정식 완료 기준은 Proposed | 저장 원자성/영속성, idempotency key, 내용 변경, 취소·만료·재입장, 동순위, bot 방어와 유형별 대기열은 [Q-003](../00-governance/assumptions-and-open-questions.md)에서 OPEN. | [BR-001](business-rules.md), [Q-003](../00-governance/assumptions-and-open-questions.md), [D-019~D-022](../00-governance/decision-log.md) |

현재까지 불변조건 위반이 측정/실험된 것은 없다. 합성 실험의 예정 기준은 [Experiment Registry](../08-experiments/experiment-registry.md)의 EXP 항목을 따른다.
