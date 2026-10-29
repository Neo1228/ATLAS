---
id: DOC-002
title: Assumptions and Open Questions
status: Draft
owner: Project Owner
updated: 2026-10-29
links:
  requirements: []
  adr: [ADR-0001]
  experiments: []
---
# 가정 및 미결정 사항

`CONFIRMED`는 사용자가 승인했거나 이 저장소의 기준 문서에 확정 사실로 기록된 내용, `PROPOSED`는 승인 전 제안, `ASSUMPTION`은 검증되지 않은 가정, `OPEN`은 아직 정하지 않은 사항이다. 일부 baseline이 정해져도 세부사항이 남으면 해당 질문은 계속 `OPEN`이다.

| ID | 상태 | 질문/리스크 | 권장 논의 순서 | 추적 |
|---|---|---|---|---|
| Q-001 | Resolved | 실제 사용자 문제를 검증된 것으로 주장하지 않고 가상 학습 시나리오로 시작한다. 구매자/판매자 목표는 D-015~D-017에서 정했으며, 시장 검증은 하지 않았다. | 완료 | [D-005](decision-log.md) |
| Q-010 | OPEN | 판매 유형별 행동·작업 흐름과 공통 목표에서 달라지는 점은 무엇인가? 공통 구매자/판매자 목표는 D-015~D-017에서 정했지만 유형별 행동은 아직 조사·합의하지 않았다. | Gate 1 discovery; 기본 목표는 완료된 [Issue #19](https://github.com/Neo1228/ATLAS/issues/19)에 기록 | [Personas & Jobs](../01-discovery/personas-and-jobs.md) |
| Q-002 | OPEN | 판매 단위가 SKU/수량인지 좌석·예약권 등인지, 캠페인 재고와 전체 재고는 어떤 관계인가? | 판매 유형별 행동 논의와 함께 | [Domain Rules](../03-domain/business-rules.md), [Personas](../01-discovery/personas-and-jobs.md) |
| Q-003 | OPEN | 대기열 순서의 발급·재시도·재시작 기준은 D-020~D-022에서 정했지만, ticket의 영속성, idempotency key 범위/보존 기간, 요청 내용 변경, 만료·재입장, 동순위, bot 방어와 판매 유형별 정책은 어떻게 할까? | 기준은 완료된 [Issue #24](https://github.com/Neo1228/ATLAS/issues/24)에 기록. 나머지는 Q-002와 함께 합의 | [Business Rules](../03-domain/business-rules.md), [Invariants](../03-domain/invariants.md) |
| Q-004 | OPEN | 예약 TTL 만료와 결제 진행/성공이 경합하면 주문·재고·결제는 어떻게 수렴해야 할까? | Q-002 뒤 | [FR-002](../02-requirements/requirements-index.md), [Experiments](../08-experiments/experiment-registry.md) |
| Q-005 | OPEN | Authorization/Capture 시점, 재시도, 결제 UNKNOWN 처리와 환불 정책은 무엇인가? | Q-010 논의 뒤 | [FR-002](../02-requirements/requirements-index.md), [Experiments](../08-experiments/experiment-registry.md) |
| Q-006 | OPEN | 내부 정산 원장과 PSP 실제 지급의 경계, 수수료·주기·보류·사후 정정은 무엇인가? | PSP 제품/지역 조사 필요 | [FR-003](../02-requirements/requirements-index.md) |
| Q-007 | OPEN | 대상 지역·통화·세금·개인정보·보안/규제 범위 및 합성 데이터 정책은? | 테스트 계획 전 | [NFR-001](../02-requirements/requirements-index.md), [EXP-007](../08-experiments/experiment-registry.md) |
| Q-008 | OPEN | 합성 부하(synthetic workload)의 요청률(RPS), 동시 요청 수, 요청 구성(request mix), 급증 부하(burst), p95/p99 지연시간, 비용 한도, 서비스 수준 목표(SLO)·복구시간(RTO)·복구시점(RPO), 배포 환경은 무엇인가? 숫자는 사용자 합의 전 TBD다. 제안된 순서는 Gate 2에서 workload profile 합의 → Gate 3에서 capacity model 작성 → Gate 4에서 synthetic validation이다 ([Issue #20](https://github.com/Neo1228/ATLAS/issues/20); EXP-001/002/009). | Gate 2 요구사항 합의 | [Requirements](../02-requirements/requirements-index.md), [Experiments](../08-experiments/experiment-registry.md) |
| Q-009 | OPEN | Java/Spring/DB/인프라 버전과 PayPal 제품별 API 가용 범위는? | 관련 ADR/구현 직전 공식 확인 | [ADR-0001](../06-decisions/adr/ADR-0001-architecture-style.md), [Reference Register](reference-register.md) |

## 기존 제약
장기 도메인 A/B/C 유지, PayPal Sandbox 우선, Fake PSP를 별도로 다룬다. 실결제·실개인정보·실지급은 초기 제외. `Modular Monolith First`는 Proposed 가설이지 Accepted 결정이 아니다.
