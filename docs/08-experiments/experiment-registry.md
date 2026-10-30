---
id: DOC-007
title: Experiment Registry
status: Draft
owner: Project Owner
updated: 2026-10-30
links:
  requirements: [NFR-002]
  experiments: [EXP-001, EXP-002, EXP-003, EXP-004, EXP-005, EXP-006, EXP-007, EXP-008, EXP-009, EXP-010, EXP-011]
---
# 실험 레지스트리

모든 실험은 ○ Planned. 실행 결과 없음. 재고 100/1,000은 실험 후보 수량이며 목표값 아님. 합성 부하는 실제 트래픽과 다름.

제안 순서 ([D-018](../00-governance/decision-log.md), △ Proposed):
- Gate 2: workload profile 합의.
- Gate 3: capacity estimate.
- 구현 승인 후 Gate 4 validation.
- [Issue #20](https://github.com/Neo1228/ATLAS/issues/20), [Roadmap](../09-delivery/roadmap.md), [Glossary](../01-discovery/glossary.md).

각 실행에서 지표 단위와 측정 범위를 기록:
- Offered/admitted Buyer sessions/s, generator에서 누락된 session/iteration.
- Stage별 offered/achieved request RPS. TPS는 완료된 Buyer session만 계산; DB query 범위를 정한 경우에만 QPS.
- Throughput, concurrency, p95/p99 latency, error/expiry/abandonment.
- Load model, request/transaction mix, payload, think time, burst/ramp, duration.
- Queue depth/age, inventory hold occupancy, 측정 가능한 resource saturation.

| ID | 테스트 가설 | 주요 제약 |
|---|---|---|
| EXP-001 | [D-023/D-035](../00-governance/decision-log.md) 초기 campaign pool: reservation당 1개. 동시 reservation과 claim/release 전이 뒤 oversell이 없고, `available + reserved + claimed = initial capacity`, 각 수량 ≥ 0이 유지되는지 확인. | 100/1,000은 후보 입력만; 목표값 아님. 실험 미실행. [Issue #62](https://github.com/Neo1228/ATLAS/issues/62), [PR #63](https://github.com/Neo1228/ATLAS/pull/63). |
| EXP-002 | Conditional UPDATE와 pessimistic/optimistic lock의 throughput·p95/p99·대기시간 비교 | 같은 DB·환경·부하 조건 필요 |
| EXP-003 | [D-025~D-027](../00-governance/decision-log.md): capture success 후 claim; UNKNOWN/PENDING 중 release 금지. Same-attempt reconciliation, 중복 claim/retry 없음 검증. | Endpoint retry/idempotency support·retention·limit TBD ([Q-005](../00-governance/assumptions-and-open-questions.md)) |
| EXP-004 | [D-025~D-027](../00-governance/decision-log.md): TTL/cancel, capture/void states, delayed webhook, restart. Capture 확인 → active authorization void → non-payment와 void/expiry 확인 후 release. | Fake PSP는 PayPal 동작 증거 아님; deadline/escalation TBD ([Q-004/Q-005](../00-governance/assumptions-and-open-questions.md)) |
| EXP-005 | [D-025~D-030](../00-governance/decision-log.md): 10분 fixed test TTL. 만료 전·후 AUTHORIZE, PENDING/UNKNOWN, extension 금지 검증. Hold-to-submit p50/p95/p99, expiry/abandonment, occupancy, oversell 측정. | 결과 후 재평가; production TTL/SLO TBD ([Q-004/Q-008](../00-governance/assumptions-and-open-questions.md)) |
| EXP-006 | Refund·adjustment·fee 변경 뒤 settlement ledger 정합성 | 정산 정책 TBD |
| EXP-007 | ID 변경으로 다른 tenant 데이터 접근 시도 (IDOR) | 권한/threat boundary TBD |
| EXP-008 | Cache update 역전, event 중복·순서 변경, consumer restart 영향 | Cache/messaging stack TBD |
| EXP-009 | Failure injection 뒤 recovery time과 허용 data loss 측정 | RTO/RPO/SLO/environment TBD |
| EXP-010 | [D-031~D-033](../00-governance/decision-log.md): full Buyer journey, hybrid arrivals, 10k/60m baseline, 2×/4× stress; report-only. | Ticketmaster는 균등 분배 가정의 rough estimate. Sessions/s, stage RPS, resolved TPS, p95/p99, errors, hold, expiry, oversell 측정. Burst/env/production target OPEN ([Q-008](../00-governance/assumptions-and-open-questions.md)) |
| EXP-011 | [D-034](../00-governance/decision-log.md): active ticket의 same key·same payload retry가 기존 ticket/sequence를 재사용하는지, payload 변경이 거절되는지 확인. Expiry 후 새 key로 재입장하면 새 unique sequence가 tail에 발급되는지, 동시 admission·restart 뒤에도 순서와 ticket이 보존되는지 검증. | Key scope/retention, payload 비교 필드, expiry trigger/duration, storage/atomicity는 OPEN ([Q-003](../00-governance/assumptions-and-open-questions.md)) |

실험 기록: 요구사항·불변조건, 완료 기준, 환경, 명령, 반복 횟수, 지표, 교란 요인, 한계. 형식: [Experiment Template](TEMPLATE.md).
