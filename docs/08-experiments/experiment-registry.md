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

각 run에서 metric unit/scope를 명시:
- Offered vs achieved RPS. TPS는 transaction 범위, QPS는 DB query 범위 정의.
- Throughput, concurrency, p95/p99 latency, error rate.
- Load model (open/closed), request mix, payload, burst/ramp, duration.
- Queue depth/age와 resource saturation (측정 대상인 경우).

| ID | 테스트 가설 | 주요 제약 |
|---|---|---|
| EXP-001 | [D-023](../00-governance/decision-log.md) 초기 범위: campaign 소유 pool 하나에서 수량형 SKU 1종의 동시 reservation 중 초과 예약·판매 발생 여부 확인 | 100/1,000은 실험 후보 수량이지 목표값 아님. 정책/acceptance TBD |
| EXP-002 | conditional UPDATE, pessimistic/optimistic lock의 throughput·p95/p99·대기 비교 | 동일 DB·환경·부하 필요 |
| EXP-003 | [D-025/D-026](../00-governance/decision-log.md): authorize 후 capture. Capture success 확인 전 claim 금지. 각 operation에서 ambiguous response를 주입하고 same-attempt reconciliation, 중복 claim/new attempt 방지를 검증. | Operation별 idempotency 지원/retention, retry policy 미정 ([Q-005](../00-governance/assumptions-and-open-questions.md)) |
| EXP-004 | [D-025/D-026](../00-governance/decision-log.md): authorization/capture 응답 유실·지연, 늦은 webhook, restart 상황. Authorization만으로 claim하지 않고 operation별 UNKNOWN을 reconcile하는지 확인. | Fake PSP는 PayPal 장애 증거가 아님; recovery acceptance 미정 ([Q-005](../00-governance/assumptions-and-open-questions.md)) |
| EXP-005 | [D-025/D-026](../00-governance/decision-log.md): authorization 또는 capture UNKNOWN 중 inventory TTL 경과. Reconciliation 전 재고가 unavailable인지 확인. | TTL, authorization 확정 후 expiry, capture/void recovery, compensation 미정 ([Q-004/Q-005](../00-governance/assumptions-and-open-questions.md)) |
| EXP-006 | refund·adjustment·fee 변경 뒤 settlement ledger 정합성 | settlement policy TBD |
| EXP-007 | ID 변경으로 다른 tenant 데이터 접근 시도 (IDOR) | 권한/threat boundary TBD |
| EXP-008 | cache update 역전·event 중복/순서 변경·consumer restart 영향 | cache/messaging stack TBD |
| EXP-009 | failure injection 후 recovery time·허용 데이터 손실 측정 | RTO/RPO/SLO/environment TBD |

Run 기록에 requirements/invariants, acceptance, 환경, commands, repetitions, metrics, confounders, limits 포함. 형식: [Experiment Template](TEMPLATE.md).
