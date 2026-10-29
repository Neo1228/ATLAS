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
Document type: Reference

불변조건은 모든 정상/실패 전이 후 지켜야 할 성질이다. 수치/저장소 설계/검증은 Gate 2~4에서 결정한다.

| ID | 조건 | 상태 | 전제 / OPEN 경계 | 연결 |
|---|---|---|---|---|
| INV-001 | 한 판매 단위의 확정된 예약/판매 수량은 그 단위의 판매 가능 capacity를 넘지 않는다. | Proposed; seller objective에서 도출 | 판매 단위, 재입고·조정·취소·반품 semantics, 여러 캠페인 간 capacity 공유는 Q-002 OPEN. Capacity를 count하는 정본 저장소/원자성은 Gate 3 설계 대상. | BR-002, FR-001, EXP-001, EXP-005 |
| INV-002 | 동일 대기열의 자원 경합에서 서버 순번이 낮은 유효 요청이 먼저 기회를 받는다. Admission은 자격 확인 후 순번을 발급하며, 같은 논리 retry는 ticket을 재사용하고 worker/service restart 후 순번을 보존한다. | Confirmed baseline (D-019~D-022); formal acceptance Proposed | Atomic/durable issuance, idempotency key scope/retention, changed payload, cancel/expiry/re-entry, tie, bot fairness and cross-type queue semantics remain Q-003 / Issue #24 OPEN. | BR-001, Q-003, D-019~D-022 |

현재까지 불변조건 위반이 측정/실험된 것은 없다. 합성 실험의 예정 기준은 `docs/08-experiments/experiment-registry.md`의 EXP 항목을 따른다.
