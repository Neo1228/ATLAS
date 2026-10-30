---
id: DOC-007
title: Experiment Registry
status: Draft
owner: Project Owner
updated: 2026-10-30
links:
  requirements: [NFR-002]
  experiments: [EXP-001, EXP-002, EXP-003, EXP-004, EXP-005, EXP-006, EXP-007, EXP-008, EXP-009]
---
# 실험 레지스트리

상태: 모든 실험 ○ Planned. 실행/결과 없음. 재고 100/1,000은 테스트 후보이지 목표 아님. Synthetic workload는 실제 traffic과 다름.

제안 순서 ([D-018](../00-governance/decision-log.md), △ Proposed):
- Gate 2: workload profile 합의.
- Gate 3: capacity model 추정.
- Implementation approval 후 Gate 4 validation.
- [Issue #20](https://github.com/Neo1228/ATLAS/issues/20), [Roadmap](../09-delivery/roadmap.md), [Glossary](../01-discovery/glossary.md).

Each run records metric units/scopes:
- Offered/admitted Buyer sessions/s and dropped sessions/iterations.
- Offered/achieved request RPS by stage. TPS is resolved business Buyer-session completions; QPS only when DB query scope is explicit.
- Throughput, concurrency, p95/p99 latency, error/expiry/abandonment.
- Load model, request/transaction mix, payload, think time, burst/ramp and duration.
- Queue depth/age, inventory hold occupancy and resource saturation where measured.

| ID | 테스트 가설 | 주요 제약 |
|---|---|---|
| EXP-001 | [D-023](../00-governance/decision-log.md) 초기 범위: campaign 소유 pool 하나에서 수량형 SKU 1종의 동시 reservation 중 초과 예약·판매 발생 여부 확인 | 100/1,000은 실험 후보 수량이지 목표값 아님. 정책/acceptance TBD |
| EXP-002 | conditional UPDATE, pessimistic/optimistic lock의 throughput·p95/p99·대기 비교 | 동일 DB·환경·부하 필요 |
| EXP-003 | [D-025~D-027](../00-governance/decision-log.md): Capture success only claim; capture UNKNOWN/PENDING blocks release. Verify same-attempt reconciliation, no duplicate claim/retry. | Endpoint retry/idempotency support/retention and limit TBD ([Q-005](../00-governance/assumptions-and-open-questions.md)) |
| EXP-004 | [D-025~D-027](../00-governance/decision-log.md): TTL/cancel, capture pending/declined/unknown, void pending/unknown, delayed webhook and restart. Verify capture check → void active auth → release only after confirmed void/expiry and non-payment. | Fake PSP does not prove PayPal behavior; deadlines/escalation TBD ([Q-004/Q-005](../00-governance/assumptions-and-open-questions.md)) |
| EXP-005 | [D-025~D-030](../00-governance/decision-log.md): test 10-minute synthetic fixed TTL from confirmed hold; check AUTHORIZE before/after expiry, PENDING/UNKNOWN outcome and no-extension behavior. Measure hold-to-submission p50/p95/p99, expiry/abandonment, hold occupancy and oversell invariant. | 10 minutes is test-only; re-evaluate after results. Production TTL/SLO, workload and acceptance remain TBD ([Q-004/Q-008](../00-governance/assumptions-and-open-questions.md)) |
| EXP-006 | refund·adjustment·fee 변경 뒤 settlement ledger 정합성 | settlement policy TBD |
| EXP-007 | ID 변경으로 다른 tenant 데이터 접근 시도 (IDOR) | 권한/threat boundary TBD |
| EXP-008 | cache update 역전·event 중복/순서 변경·consumer restart 영향 | cache/messaging stack TBD |
| EXP-009 | failure injection 후 recovery time·허용 데이터 손실 측정 | RTO/RPO/SLO/environment TBD |
| EXP-010 | [D-031](../00-governance/decision-log.md): Full Buyer session under hybrid model (open-loop arrivals; sequential per-session steps) across baseline, sale-open burst and step-up phases. | Numeric rates/durations, think-time distribution, test environment and acceptance TBD ([Q-008](../00-governance/assumptions-and-open-questions.md)); record offered/admitted sessions/s, dropped arrivals, RPS by stage, resolved-session TPS, p95/p99, errors, backlog/hold occupancy, expiry and invariant results. |

Run 기록에 requirements/invariants, acceptance, 환경, commands, repetitions, metrics, confounders, limits 포함. 형식: [Experiment Template](TEMPLATE.md).
