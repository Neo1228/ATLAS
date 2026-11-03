---
id: DOC-007
title: Experiment Registry
status: Draft
owner: Project Owner
updated: 2026-11-03
links:
  requirements: [FR-001, FR-002, FR-003, NFR-001, NFR-002]
  adr: [ADR-0001, ADR-0002, ADR-0007]
  experiments: [EXP-001, EXP-002, EXP-003, EXP-004, EXP-005, EXP-006, EXP-007, EXP-008, EXP-009, EXP-010, EXP-011, EXP-012, EXP-013, EXP-014]
---
# 실험 레지스트리

Most registered experiments remain `○ Planned`. EXP-013 is a completed 20-minute local calibration report, not a capacity or production measurement. M1 integration/HTTP smoke coverage and this calibration do not establish production performance.

- Gate 2 report-only workload approved: [Issue #66](https://github.com/Neo1228/ATLAS/issues/66), [PR #67](https://github.com/Neo1228/ATLAS/pull/67).
- Local M1 runtime implemented and merged in [PR #73](https://github.com/Neo1228/ATLAS/pull/73); the later Gate 4 calibration is recorded as EXP-013 and makes no capacity claim.

Proposed verification sequence ([D-018](../00-governance/decision-log.md), △ Proposed):
- Gate 2 workload profile approved.
- Gate 3 capacity model incomplete; assumptions remain distinct from measurements.
- Local M1 runtime complete.
- Gate 4 EXP-013 20-minute local calibration completed; the selected K=1 profile produced no WAITING/409. Any higher-arrival queue-contention profile needs a new explicit scope.
- [Issue #20](https://github.com/Neo1228/ATLAS/issues/20), [Roadmap](../09-delivery/roadmap.md), [용어집](../01-discovery/glossary.md).

각 실행에서 지표의 단위와 측정 범위를 함께 기록:
- Offered/admitted Buyer sessions/s, generator에서 누락된 session/iteration.
- Stage별 offered/achieved request RPS. TPS는 완료된 Buyer session만 계산. DB query 범위를 정한 경우에만 QPS 사용.
- Throughput, concurrency, p95/p99 latency, error/expiry/abandonment.
- Load model, request/transaction mix, payload, think time, burst/ramp, duration.
- Queue depth/age, inventory hold occupancy, 측정 가능한 resource saturation.

| ID | 테스트 가설 | 주요 제약 |
|---|---|---|
| EXP-001 | [D-023/D-035](../00-governance/decision-log.md) 기준 초기 campaign pool에서 reservation당 1개 할당. 동시 reservation과 claim/release 뒤 oversell이 없고 `available + reserved + claimed = initial capacity`, 각 수량 ≥ 0이 유지되는지 확인. | 100/1,000은 후보 입력이며 목표값 아님. 미실행. [Issue #62](https://github.com/Neo1228/ATLAS/issues/62), [PR #63](https://github.com/Neo1228/ATLAS/pull/63). |
| EXP-002 | PostgreSQL inventory reservation 방식 비교: conditional UPDATE와 pessimistic/optimistic locking. 같은 workload에서 throughput, p95/p99, lock wait, transaction failure 측정. | PostgreSQL 선택. schema/isolation과 benchmark 환경 미정. 측정 전 방식 선택 금지. |
| EXP-003 | [D-025~D-027](../00-governance/decision-log.md) 기준 capture success 뒤 claim, UNKNOWN/PENDING 중 release 금지. 동일 attempt의 reconciliation과 중복 claim/retry 부재 확인. | Endpoint retry/idempotency 지원·retention·limit 미정 ([Q-005](../00-governance/assumptions-and-open-questions.md)). |
| EXP-004 | [D-025~D-027](../00-governance/decision-log.md) 기준 TTL/cancel, capture/void 상태, delayed webhook, restart 확인. Capture 확인 → active authorization void → non-payment와 void/expiry 확인 뒤 release. | Fake PSP는 PayPal 동작 증거가 아님. Deadline/escalation 미정 ([Q-004/Q-005](../00-governance/assumptions-and-open-questions.md)). |
| EXP-005 | [D-025~D-030](../00-governance/decision-log.md) 기준 10분 fixed test TTL. 만료 전·후 AUTHORIZE, PENDING/UNKNOWN, extension 금지 확인. Hold-to-submit p50/p95/p99, expiry/abandonment, occupancy, oversell 측정. | 결과 검토 후 재평가. Production TTL/SLO 미정 ([Q-004/Q-008](../00-governance/assumptions-and-open-questions.md)). |
| EXP-006 | Refund·adjustment·fee 변경 뒤 settlement ledger 정합성 확인. | 정산 정책 미정. |
| EXP-007 | ID 변경으로 다른 Tenant의 데이터에 접근하는 IDOR 시도. | 권한·threat boundary 미정. |
| EXP-008 | Cache update 역전, event 중복·순서 변경, consumer restart 영향 확인. | Cache/messaging stack 미정. |
| EXP-009 | Failure injection 뒤 recovery time과 허용 data loss 측정. | RTO/RPO/SLO/environment 미정. |
| EXP-010 | [D-031~D-033](../00-governance/decision-log.md) 기준 full Buyer journey, hybrid arrivals, 10k/60m baseline, 2×/4× stress 확인. 결과는 report-only. | Ticketmaster 수치는 균등 분배 가정의 rough estimate. Sessions/s, stage RPS, resolved TPS, p95/p99, errors, hold, expiry, oversell 측정. Burst/env/production target 미정 ([Q-008](../00-governance/assumptions-and-open-questions.md)). |
| EXP-011 | [D-034/D-039](../00-governance/decision-log.md) 기준 active ticket의 same key·same payload retry가 기존 ticket/sequence를 재사용하고 payload 변경은 거절되는지 확인. Expiry 뒤 새 key로 재입장하면 새 unique sequence가 tail에 발급되는지, 동시 admission·restart 뒤에도 committed order와 ticket이 보존되는지 검증. | Campaign counter row + ticket 단일 transaction이 설계 baseline. Key scope/retention, payload 비교 필드, expiry trigger/duration, exact schema, lock timeout/deadlock recovery 미정 ([Q-003](../00-governance/assumptions-and-open-questions.md)). |
| EXP-012 | [D-037](../00-governance/decision-log.md) 기준 PostgreSQL DB-first baseline을 만든 뒤, load test에서 bottleneck이 확인된 경우에만 별도 queue/cache 대안 비교. 같은 offered workload에서 sessions/s, stage RPS, resolved TPS, p95/p99, errors, DB connection/lock pressure, queue age, invariant 정합성, 운영 비용 측정. | Gate 4 후보. 별도 store는 미선택. Production SLO pass/fail 기준 없음. 실행 가능한 baseline과 승인된 환경이 준비되기 전 실행 금지. |
| EXP-013 | Calibrate the existing Buyer flow at the selected 1× rate in a normal-stock baseline and a 10%-stock/K=1 queue configuration; measure dropped iterations, outcomes, latency and inventory invariants. | Windows 10/Docker (4 CPUs/~3.83 GiB), k6 `2.3.0`, 10m/profile. Baseline: 1,661 paid, 6 dropped. Queue: 167 paid, 1,500 stockouts, WAITING/409=0; inventory conservation held. No actual queue contention or capacity claim. [Report](EXP-013-gate4-20-minute-calibration.md), [Issue #81](https://github.com/Neo1228/ATLAS/issues/81). |
| EXP-014 | Compare UUIDv4 PK/public ID, UUIDv7 PK/public ID, and BIGINT identity PK + UUIDv7 public ID on PostgreSQL 18 using 100k parents/200k children; five randomized insert and set-query runs. Owner selected UUIDv7 PK/public UUID IDs ([D-056](../00-governance/decision-log.md), [ADR-0007](../06-decisions/adr/ADR-0007-uuidv7-identifiers.md)): UUIDv7 parent PK index 3,178,496 bytes vs UUIDv4 4,407,296; parent insert p50 211.49 vs 212.64 ms; combined rows/s p50 183,503.1 vs 184,479.5. BIGINT hybrid showed no combined-throughput advantage. | Local result only; generation/commit excluded, raw timing variance and resource contention uncertain. Set-based lookup used parent sequential scans. Testcontainers/JPA verified Hibernate UUIDv7 PK generation in actual entity flows; Hibernate generator remains incubating. No production claim. [Report](EXP-014-id-strategy-comparison.md), [SQL](EXP-014-id-strategy-comparison.sql). |

실험 기록에는 요구사항·불변조건, 완료 기준, 환경, 명령, 반복 횟수, 지표, 교란 요인, 한계를 포함. 형식: [실험 기록 양식](TEMPLATE.md).
