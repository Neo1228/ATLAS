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
| Gate 1 | 문제·사용자 목표·도메인 충돌 탐색 | → 진행 중; [D-023~D-025](../00-governance/decision-log.md) initial baselines 선택. [Q-002/Q-003/Q-010](../00-governance/assumptions-and-open-questions.md) 잔여 범위와 [Q-004/Q-005](../00-governance/assumptions-and-open-questions.md) 세부 정책 ? OPEN |
| Gate 2 | 요구사항·예외·acceptance/invariants·workload profile | ? 대기 |
| Gate 3 | 모듈·상태·데이터·계약·위험·capacity estimate | ? 대기 |
| Gate 4 | 구현 및 별도 승인된 기능의 synthetic load/failure 검증 | △ 제안; 독립 승인된 Gate 아님 |
| M1 | Campaign, inventory reservation, order intent, concurrency | ○ 계획 |
| M2 | PayPal Sandbox/Fake PSP, payment state/recovery/idempotency | ○ 계획 |
| M3 | Queue/cache/event, concurrency, bottleneck 비교 ([EXP-001/002/008](../08-experiments/experiment-registry.md)) | ○ 계획 |
| M4 | Tenant security, ledger, settlement, reconciliation, refund/adjustment | ○ 계획 |
| M5 | Observability, backup/recovery, SLO/RTO/RPO 실험 | ○ 계획 |
| M6 | 대안 비교, ADR 정리, portfolio evidence | ○ 계획 |

## 초기 문서 작업 묶음
WP는 초기 문서 작업 이력 ID. 이후 Issue/PR 개수나 분할 기준 아님.

| WP | 범위 | 기록 |
|---|---|---|
| WP-001 | 저장소 진입점·GitHub 기초 | main 게시 |
| WP-002 | 거버넌스 | [PR #9](https://github.com/Neo1228/ATLAS/pull/9) 병합 |
| WP-003 | discovery·requirements·domain | [PR #10](https://github.com/Neo1228/ATLAS/pull/10), [#21](https://github.com/Neo1228/ATLAS/pull/21) 병합 |
| WP-004 | ADR·experiments | [PR #11](https://github.com/Neo1228/ATLAS/pull/11) 병합 |
| WP-005 | roadmap·delivery records | [PR #12](https://github.com/Neo1228/ATLAS/pull/12), [#18](https://github.com/Neo1228/ATLAS/pull/18) 병합 |

## Capacity/performance 순서 ([D-018](../00-governance/decision-log.md), △ Proposed)
- **Gate 2 — load:** request/transaction mix; offered/achieved RPS; TPS with explicit business/DB transaction scope; QPS only if DB scope applies.
- **Gate 2 — model:** concurrency; open/closed model; payload; burst/ramp; test duration.
- **Gate 2 — targets:** p95/p99 latency; error rate; budget; SLO/RTO/RPO.
- 목표 수치와 profile 값은 합의 전 TBD.
- **Gate 3:** `docs/05-architecture/capacity-model.md` 작성 예정; 현재 파일 없음. 합의된 profile과 DB/infra 조건으로 용량 추정. 추정은 측정값 아님.
- **Gate 4:** 구현 승인 뒤 synthetic load/failure로 추정 검증. [EXP-001/002/009](../08-experiments/experiment-registry.md)는 ○ Planned.
- 추적: [Q-008](../00-governance/assumptions-and-open-questions.md), [NFR-002](../02-requirements/requirements-index.md), [Issue #20](https://github.com/Neo1228/ATLAS/issues/20).

## 현재 상태
- WP-001~WP-005 main 게시. Git 규칙 [PR #4](https://github.com/Neo1228/ATLAS/pull/4), batching [PR #16](https://github.com/Neo1228/ATLAS/pull/16), delivery [PR #18](https://github.com/Neo1228/ATLAS/pull/18) 병합.
- [Issue #37](https://github.com/Neo1228/ATLAS/issues/37)에서 `AUTHORIZE → CAPTURE` 선택; [D-026](../00-governance/decision-log.md)에 capture success 확인 후 claim으로 기록. [PR #41](https://github.com/Neo1228/ATLAS/pull/41) 병합.
- Gate 1 진행 중. [D-023~D-027](../00-governance/decision-log.md)은 초기 domain/recovery 기준; 구현 승인 아님.
- [Q-003](../00-governance/assumptions-and-open-questions.md)의 storage/key/payload/expiry/re-entry/tie/bot 정책은 OPEN. 관련 경계는 Issue를 나누지 않고 함께 논의.
- [Q-004/Q-005/Q-009](../00-governance/assumptions-and-open-questions.md): [D-027](../00-governance/decision-log.md) release boundary, [D-028](../00-governance/decision-log.md) fixed checkout window. [Issue #44](https://github.com/Neo1228/ATLAS/issues/44)/[PR #45](https://github.com/Neo1228/ATLAS/pull/45). Duration/start trigger, provider deadline, retry/key retention and escalation/compensation remain OPEN; implementation not approved.
- [Q-008](../00-governance/assumptions-and-open-questions.md) 성능/트래픽 목표는 Gate 2 전까지 TBD. 코드·runtime dependency·deployment 미선택.
