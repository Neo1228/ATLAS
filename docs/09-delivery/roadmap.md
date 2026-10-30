---
id: DOC-008
title: Roadmap
status: Draft
owner: Project Owner
updated: 2026-10-30
links:
  requirements: []
  adr: [ADR-0001]
  experiments: []
---
# 로드맵

Gate는 사용자 승인이 필요한 점검 단계. 명시적 승인 없이 상태 변경 금지. 작은 작업마다 별도 Gate를 만들지 않음.

| 단계 | 결과 | 상태 |
|---|---|---|
| Gate 0 | 프로젝트 범위·문서 기준 | ✓ 완료, 2026-10-29 승인 |
| Gate 1 | 문제·사용자 목표·도메인 탐색 | → 진행 중; [D-023~D-025](../00-governance/decision-log.md) 초기 기준 선택. [Q-002/Q-003/Q-010](../00-governance/assumptions-and-open-questions.md) 잔여 범위, [Q-004/Q-005](../00-governance/assumptions-and-open-questions.md) 세부 정책 OPEN |
| Gate 2 | 요구사항·예외·완료 기준·불변조건·workload profile | ? 대기 |
| Gate 3 | 모듈·상태·데이터·계약·위험·용량 추정 | ? 대기 |
| Gate 4 | 구현 승인된 기능의 synthetic load/failure 검증 | △ Proposed; 별도 Gate 승인 아님 |
| M1 | Campaign, inventory reservation, order intent, 동시성 | ○ 계획 |
| M2 | PayPal Sandbox/Fake PSP, payment state/recovery/idempotency | ○ 계획 |
| M3 | Queue/cache/event, 동시성·bottleneck 비교 ([EXP-001/002/008](../08-experiments/experiment-registry.md)) | ○ 계획 |
| M4 | Tenant security, ledger, settlement, reconciliation, refund/adjustment | ○ 계획 |
| M5 | Observability, backup/recovery, SLO/RTO/RPO 실험 | ○ 계획 |
| M6 | 대안 비교, ADR 정리, portfolio 근거 | ○ 계획 |

## 초기 문서 작업 묶음
WP는 초기 문서 작업 이력 ID. 이후 Issue/PR 개수나 분할 기준 아님.

| WP | 범위 | 기록 |
|---|---|---|
| WP-001 | 저장소 진입점·GitHub 기초 | main 게시 |
| WP-002 | 거버넌스 | [PR #9](https://github.com/Neo1228/ATLAS/pull/9) 병합 |
| WP-003 | discovery·requirements·domain | [PR #10](https://github.com/Neo1228/ATLAS/pull/10), [#21](https://github.com/Neo1228/ATLAS/pull/21) 병합 |
| WP-004 | ADR·experiments | [PR #11](https://github.com/Neo1228/ATLAS/pull/11) 병합 |
| WP-005 | roadmap·delivery records | [PR #12](https://github.com/Neo1228/ATLAS/pull/12), [#18](https://github.com/Neo1228/ATLAS/pull/18) 병합 |

## 용량·성능 검증 순서 ([D-018](../00-governance/decision-log.md), △ Proposed)
- **Gate 2 — workload:** request/transaction mix, offered·achieved RPS, transaction 범위를 명시한 TPS. DB query 측정 시에만 QPS.
- **Gate 2 — model:** concurrency, open/closed model, payload, burst/ramp, duration.
- **Gate 2 — 목표:** p95/p99 latency, error rate, budget, SLO/RTO/RPO. 수치 목표는 합의 전 TBD.
- **Gate 3:** 합의한 profile과 DB/infra 조건으로 capacity model 작성. 추정은 측정값이 아님. `docs/05-architecture/capacity-model.md` 예정; 현재 없음.
- **Gate 4:** 구현 승인 후 synthetic load/failure test로 추정 검증. [EXP-001/002/009](../08-experiments/experiment-registry.md)는 ○ Planned.
- 추적: [Q-008](../00-governance/assumptions-and-open-questions.md), [NFR-002](../02-requirements/requirements-index.md), [Issue #20](https://github.com/Neo1228/ATLAS/issues/20).

## 현재 상태
- WP-001~WP-005 main 게시. Git 규칙 [PR #4](https://github.com/Neo1228/ATLAS/pull/4), batching [PR #16](https://github.com/Neo1228/ATLAS/pull/16), delivery [PR #18](https://github.com/Neo1228/ATLAS/pull/18) 병합.
- [Issue #37](https://github.com/Neo1228/ATLAS/issues/37)에서 AUTHORIZE → CAPTURE 선택. [D-026](../00-governance/decision-log.md): capture success 확인 후 inventory claim. [PR #41](https://github.com/Neo1228/ATLAS/pull/41) 병합.
- Gate 1: [D-023~D-030](../00-governance/decision-log.md) 초기 도메인·결제 경계. [D-031~D-033](../00-governance/decision-log.md)은 Gate 2 synthetic workload shape와 측정 입력. 실제 traffic/capacity 목표와 구현은 미승인.
- [Q-003](../00-governance/assumptions-and-open-questions.md): [D-034](../00-governance/decision-log.md)에서 active ticket retry, payload 불일치 거절, 만료 후 새 key·tail sequence를 결정. [Issue #60](https://github.com/Neo1228/ATLAS/issues/60), [PR #61](https://github.com/Neo1228/ATLAS/pull/61). OPEN: key scope/retention, payload 비교 필드, expiry trigger/duration, storage/atomicity, bot 방어, 다른 판매 유형.
- [Q-008](../00-governance/assumptions-and-open-questions.md): full Buyer journey, hybrid arrivals, 10k sessions/60m synthetic baseline, 2×/4× stress, report-only.
  - Issues: [#51](https://github.com/Neo1228/ATLAS/issues/51), [#54](https://github.com/Neo1228/ATLAS/issues/54), [#56](https://github.com/Neo1228/ATLAS/issues/56).
- Merged PRs:
  - [PR #53](https://github.com/Neo1228/ATLAS/pull/53) → [5266d14](https://github.com/Neo1228/ATLAS/commit/5266d14)
  - [PR #55](https://github.com/Neo1228/ATLAS/pull/55) → [a82b454](https://github.com/Neo1228/ATLAS/commit/a82b454)
  - [PR #57](https://github.com/Neo1228/ATLAS/pull/57) → [6cf28dd](https://github.com/Neo1228/ATLAS/commit/6cf28dd)
  - OPEN: burst factor/duration, production demand/SLO, capacity, implementation.
- [Q-008](../00-governance/assumptions-and-open-questions.md) production 성능 목표 TBD. Runtime dependency와 배포 방식 미정.
