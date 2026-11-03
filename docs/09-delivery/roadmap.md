---
id: DOC-008
title: Roadmap
status: Draft
owner: Project Owner
updated: 2026-11-03
links:
  requirements: [FR-001, FR-002, FR-003, NFR-001, NFR-002]
  adr: [ADR-0001, ADR-0003, ADR-0004, ADR-0005, ADR-0006, ADR-0007]
  experiments: [EXP-001, EXP-002, EXP-003, EXP-004, EXP-005, EXP-006, EXP-007, EXP-008, EXP-009, EXP-010, EXP-011, EXP-012, EXP-013, EXP-014]
---
# 로드맵

Gate는 사용자 승인이 필요한 점검 단계. 명시적 승인 없이 상태 변경 금지. 작은 작업마다 별도 Gate를 만들지 않음.

| 단계 | 결과 | 상태 |
|---|---|---|
| Gate 0 | 프로젝트 범위·문서 기준 | ✓ 완료, 2026-10-29 승인 |
| Gate 1 | 문제·사용자 목표·도메인 탐색 | ✓ 초기 single-SKU 시나리오 완료 (2026-10-30). 판매 유형·재고 정책·대기열 세부는 [Q-002/Q-003/Q-010](../00-governance/assumptions-and-open-questions.md) 미정 |
| Gate 2 | 요구사항·예외·완료 기준·불변조건·workload profile | ✓ 현재 Buyer journey 기준 승인. 정산·보안 범위는 미정 |
| Gate 3 | Module·state·data·contract·risk·capacity estimate | ✓ Boundary/toolchain and report-only envelope recorded. Numeric capacity model not produced: request mix, environment, latency and production targets remain OPEN ([D-037~D-044](../00-governance/decision-log.md), [ADR-0001](../06-decisions/adr/ADR-0001-architecture-style.md), [ADR-0002](../06-decisions/adr/ADR-0002-runtime-technology-review.md)) |
| Gate 4 | Synthetic load/failure verification | ◐ EXP-013 20-minute local calibration complete; 0 WAITING/409, so queue contention was not measured. Broader profiles require separate owner approval; report-only, no production capacity claim ([EXP-013](../08-experiments/EXP-013-gate4-20-minute-calibration.md), [Issue #81](https://github.com/Neo1228/ATLAS/issues/81)) |
| M1 | Local single-SKU Buyer flow: Campaign, Admission, Inventory, Order/Checkout, PostgreSQL, Fake PSP, tests/CI | ✓ [PR #73](https://github.com/Neo1228/ATLAS/pull/73) merged; [Issue #72](https://github.com/Neo1228/ATLAS/issues/72) closed |
| M2 | Synthetic load/failure verification, report-only | ◐ Same workstream as Gate 4; current status and limits are stated above |
| M3 | PayPal Sandbox adapter, webhook·payment recovery | ○ Provider/API and failure/recovery policy decision required; sequencing relative to M2, credentials and scope remain OPEN |
| M4 | Seller-SKU shared stock/global FIFO and module cleanup | ✓ [PR #90](https://github.com/Neo1228/ATLAS/pull/90) merged; [Issue #89](https://github.com/Neo1228/ATLAS/issues/89) closed. Decision details: [D-049/D-055/D-056](../00-governance/decision-log.md), [ADR-0005~0007](../06-decisions/adr/ADR-0005-seller-sku-inventory-global-admission.md), [EXP-014](../08-experiments/EXP-014-id-strategy-comparison.md) |
| M5 | Observability, backup/recovery, SLO/RTO/RPO | ○ 운영 환경 선택 뒤 계획 |
| M6 | 대안 비교, ADR 정리, portfolio 근거 | ○ 계획 |

## Performance verification
- Workload/metric contract: [Q-008](../00-governance/assumptions-and-open-questions.md), [NFR-002](../02-requirements/requirements-index.md). Capacity targets remain open until workload, environment and goals are selected.
- Gate 4 uses an explicitly approved synthetic profile; results remain report-only. No production capacity/SLO claim.
