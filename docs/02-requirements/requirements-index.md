---
id: DOC-004
title: Requirements Index
status: Draft
owner: Project Owner
updated: 2026-11-03
links:
  requirements: [FR-001, FR-002, FR-003, FR-004, NFR-001, NFR-002]
  adr: [ADR-0001, ADR-0002, ADR-0005, ADR-0006]
  experiments: [EXP-001, EXP-002, EXP-003, EXP-004, EXP-005, EXP-006, EXP-007, EXP-008, EXP-009, EXP-010, EXP-011, EXP-012]
---
# 요구사항 인덱스

FR-001/FR-002/NFR-002의 Gate 2 Buyer journey 기준 승인. Seller-SKU shared stock/global FIFO 구현은 [D-049](../00-governance/decision-log.md)·[ADR-0005](../06-decisions/adr/ADR-0005-seller-sku-inventory-global-admission.md)로 결정했고 migration 방식은 [D-055](../00-governance/decision-log.md)·[ADR-0006](../06-decisions/adr/ADR-0006-v6-clean-start.md)에서 clean-start로 정함. Gate 2 범위는 소급 변경하지 않음. 우선순위와 production 목표는 미정. FR-003 settlement와 NFR-001 security는 △ Proposed.

| ID | 유형 | 상태 | 요구사항 후보 | 우선순위 | 연결 |
|---|---|---|---|---|---|
| FR-001 | 기능 | ✓ Gate 2 기준 | Seller 등록 후 Seller가 SKU 소유. Campaign은 기존 Seller-SKU를 참조하며 같은 `(sellerId, skuId)`를 사용하는 Campaign 간 재고 pool을 공유. Reservation당 1개; `available + reserved + claimed = initial capacity`; oversell 금지. Sale open 후 matching campaign/SKU request만 admission. Local runtime은 Seller-SKU 기준 bounded FIFO/K, Campaign-scoped idempotency와 ticket TTL. `sellerId`는 data identity이며 인증·권한 boundary 아님 ([D-049](../00-governance/decision-log.md)). | 미정 | [FR-004](requirements-index.md), [Q-002/Q-003/Q-007/Q-010](../00-governance/assumptions-and-open-questions.md), [BR-001/002](../03-domain/business-rules.md), [INV-001/002](../03-domain/invariants.md), [ADR-0005](../06-decisions/adr/ADR-0005-seller-sku-inventory-global-admission.md), [Issue #89](https://github.com/Neo1228/ATLAS/issues/89), [PR #90](https://github.com/Neo1228/ATLAS/pull/90) |
| FR-002 | 기능 | ✓ Gate 2 기준 | Inventory hold 후 AUTHORIZE → CAPTURE; capture success 뒤 claim. Confirmed hold부터 첫 AUTHORIZE 제출까지 10분 local test window, 연장 없음. Submission/expiry 시 queue slot 반환. PENDING/UNKNOWN이면 inventory hold 유지하고 같은 attempt로 reconciliation. 미해결 payment가 있는 same campaign + synthetic `buyerRef`의 추가 payment submission 차단 ([D-041/D-042](../00-governance/decision-log.md)). Fake PSP only; provider deadline/production policy 미정. | 미정 | [Q-004/Q-005/Q-008](../00-governance/assumptions-and-open-questions.md), [BR-003](../03-domain/business-rules.md), [INV-003](../03-domain/invariants.md), [EXP-003/004/005](../08-experiments/experiment-registry.md), [Issue #66](https://github.com/Neo1228/ATLAS/issues/66), [Issue #72](https://github.com/Neo1228/ATLAS/issues/72) |
| FR-003 | 기능 | △ Proposed | PSP transaction 대사, 내부 settlement ledger, fee, 정정과 audit trail. Contract·seller payout policy 미정. Refund execution과 post-claim 재고 처리 미결정 ([Q-002/Q-006](../00-governance/assumptions-and-open-questions.md)); 이 행에서 승인하지 않음. | 미정 | [Q-002/Q-006](../00-governance/assumptions-and-open-questions.md) |
| FR-004 | 기능 | ✓ PR #90 병합 완료 | Seller 등록 시 서버 생성 UUID 발급. Seller-SKU 등록에서 `initialStock`과 global `maxActiveCheckouts` 설정. V6는 V1–V5 application table에 행이 있으면 DDL 전에 실패하며 기존 data/schema를 변경하지 않는다. 비어 있는 V1–V5 schema만 제거하고 current Seller-SKU schema로 새로 만든다. Legacy mapping/backfill/preservation은 하지 않는다. 기존 local data는 app 밖에서 명시적으로 보관·reset해야 하며 앱은 자동 삭제하지 않는다 ([D-055](../00-governance/decision-log.md), [ADR-0006](../06-decisions/adr/ADR-0006-v6-clean-start.md)). `sellerId`는 인증·권한·Tenant 격리 수단이 아님. | 미정 | [D-049/D-055](../00-governance/decision-log.md), [ADR-0005](../06-decisions/adr/ADR-0005-seller-sku-inventory-global-admission.md), [ADR-0006](../06-decisions/adr/ADR-0006-v6-clean-start.md), [Q-002/Q-003/Q-007](../00-governance/assumptions-and-open-questions.md), [Issue #89](https://github.com/Neo1228/ATLAS/issues/89), [PR #90](https://github.com/Neo1228/ATLAS/pull/90) |
| NFR-001 | 비기능 | △ Proposed | Tenant 간 데이터·권한 격리 검증. Threat boundary 미정. | 미정 | [Q-007](../00-governance/assumptions-and-open-questions.md), [EXP-007](../08-experiments/experiment-registry.md) |
| NFR-002 | 비기능 | ✓ Gate 2 report profile | Full Buyer journey, hybrid arrivals, 10k sessions/60m synthetic baseline, 2×/4× stress. Report-only; production SLO/threshold 없음. Offered/admitted/missed sessions, stage RPS, resolved TPS, p95/p99, errors, queue/hold, expiry, oversell 기록. | 미정 | [Q-008](../00-governance/assumptions-and-open-questions.md), [EXP-001/002/009/010/012](../08-experiments/experiment-registry.md), [Issue #66](https://github.com/Neo1228/ATLAS/issues/66) |

## Gate 2 작업
- Rate scope: offered/achieved RPS; business TPS 범위; DB QPS는 측정 시에만.
- 초기 도메인 범위: campaign 1개, 수량형 SKU 1종, 독립 pool ([D-023](../00-governance/decision-log.md)); sale open 후 queue admission ([D-024](../00-governance/decision-log.md)).
- Gate 2 workload profile 승인. Concurrency, latency/error threshold, environment, production objective는 미정. 수치 목표를 추정하지 않음.

## Gate 2 승인 기준 — 현재 Buyer 여정
상태: ✓ Gate 2 기준 승인 (2026-11-02). M1 local runtime implemented in [PR #73](https://github.com/Neo1228/ATLAS/pull/73). Functional integration tests/HTTP smoke executed; registered load/failure experiments remain unrun. No performance result.

| 흐름 | 승인된 기준 | 근거 |
|---|---|---|
| 대기열 입장·재시도 | Sale open 전 admission·sequence 미발급. Open 후 eligible request만 admission. Campaign sequence unique, FIFO. Active ticket의 same-key/same-payload retry는 기존 ticket 재사용. Payload mismatch는 기존 상태 변경 없이 거절. 만료 후 새 key·tail sequence; restart 후 순서 보존. | [D-019~D-024/D-034](../00-governance/decision-log.md), [INV-002](../03-domain/invariants.md), [EXP-011](../08-experiments/experiment-registry.md) |
| 재고 예약 | Reservation당 1개. 확정 전이 후 `available + reserved + claimed = initial capacity`, 각 수량 ≥ 0. 동시 요청에서도 oversell 없음. Capture success만 claim. Release는 confirmed non-payment 후; authorization이 있으면 void/expiry 확인 필요. | [D-025~D-027/D-035](../00-governance/decision-log.md), [INV-001](../03-domain/invariants.md), [EXP-001](../08-experiments/experiment-registry.md) |
| 결제 복구 | Confirmed hold부터 initial AUTHORIZE 제출까지 10분 test window; 연장 없음. 만료 전 미제출이면 checkout 종료, payment attempt 없음. 제출된 PENDING/UNKNOWN은 TTL 만료 뒤에도 reserved 유지 후 같은 attempt reconcile; 결과 확인 전 새 attempt 금지. | [D-025~D-030](../00-governance/decision-log.md), [INV-003](../03-domain/invariants.md), [EXP-003/004/005](../08-experiments/experiment-registry.md) |
| 부하 측정 | 10k sessions/60m baseline과 2×/4× synthetic stress; report-only, pass/fail SLO 없음. Offered/admitted sessions, generator-missed sessions/iterations, stage RPS, resolved-session TPS, p95/p99, errors, queue/hold occupancy, expiry, oversell 기록. | [D-031~D-033](../00-governance/decision-log.md), [Q-008](../00-governance/assumptions-and-open-questions.md), [EXP-010](../08-experiments/experiment-registry.md) |

승인 범위는 current single-SKU Buyer journey와 report-only workload에 한정. FR-003 settlement, NFR-001 security/regulation, production SLO, provider deadline은 미승인 ([Q-006/Q-007](../00-governance/assumptions-and-open-questions.md)).

## 장기 제품 역량 지도
헌장 A/B/C와 Roadmap M1–M5의 장기 범위를 기능별로 연결. Gate 2의 current Buyer journey는 첫 검증 시나리오이며 전체 제품 범위가 아님. 기존 기준을 통합했으며 신규 기능 승인은 아님.

| 역량 영역 | 장기 범위 | 현재 기준·미결정 범위 |
|---|---|---|
| 상품·campaign·재고 | Catalog/SKU, 판매 단위, inventory pool, 재고 조정·재입고 | [FR-001/FR-004](requirements-index.md), [Q-002/Q-010](../00-governance/assumptions-and-open-questions.md), Roadmap M1/M4. Local 기준은 Seller-SKU shared pool이며 Campaign 간 공유. 다중 수량·location/channel·조정/재입고는 미정 |
| 대기열·판매 입장 | 참가 자격, 판매 개시 admission, FIFO, ticket 재시도·재입장, bot/access policy | [D-019~D-036](../00-governance/decision-log.md), [Q-003](../00-governance/assumptions-and-open-questions.md), Roadmap M3. FIFO 기준 확정; eligibility·storage·bot 정책 미정 |
| 주문·결제 복구·환불 | Reservation/hold, AUTHORIZE/CAPTURE, provider outcome 복구, void와 장기 refund | [FR-002](requirements-index.md), [Q-002/Q-004/Q-005](../00-governance/assumptions-and-open-questions.md), Roadmap M3/M4. Synthetic flow만 승인; refund execution/partial refund/post-claim stock effect는 OPEN |
| 정산·대사 | PSP transaction 대사, internal ledger, 수수료, seller payout, 회계 조정/audit trail | [FR-003](requirements-index.md), [Q-006](../00-governance/assumptions-and-open-questions.md), Roadmap M4. Proposed; refund ledger treatment와 Payment/Settlement ownership 미정 |
| 성능·복구·운영 | Capacity, observability, backup/recovery, SLO/RTO/RPO, failure test | [NFR-002](requirements-index.md), [Q-008](../00-governance/assumptions-and-open-questions.md), Roadmap M3/M5. 합성 report-only profile; production 목표·환경 미정 |

Gate 2 승인은 current single-SKU Buyer journey에 한정. 장기 역량 전체나 구현 완료를 뜻하지 않음.

## 관련 문서
- [용어집](../01-discovery/glossary.md), [가상 사용자 목표](../01-discovery/personas-and-jobs.md)
- [도메인 규칙](../03-domain/README.md), [실험 목록](../08-experiments/experiment-registry.md)
