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

`CONFIRMED`는 사용자 승인 또는 시작 프롬프트에서 확정된 사실, `PROPOSED`는 검토안, `ASSUMPTION`은 검증 전 가정, `OPEN`은 결정 대기다. Q-001은 결정됐고, Q-002 이후는 OPEN이다.

| ID | 상태 | 질문/리스크 | 권장 논의 순서 | 추적 |
|---|---|---|---|---|
| Q-001 | Resolved | 실제 사용자 문제를 검증된 것으로 주장하지 않고, 가상 학습 시나리오를 출발점으로 삼는다. 구체 페르소나/Jobs-to-be-Done은 Q-010에서 미정. | 결정됨; 상세 페르소나 논의 별도 | D-005 |
| Q-010 | OPEN | 구매자·판매자의 공통 목표/문제와 판매 유형별 차이는 무엇인가? 공통 핵심 페르소나+유형별 차이를 사용(D-015); 구매자 목표는 공정한 기회와 정확한 재고/예약 상태(D-016), 판매자는 초과판매 없이 공정한 한정판매 운영(D-017). 유형별 행동은 미정. | Gate 1 discovery; [Issue #19](https://github.com/Neo1228/ATLAS/issues/19) | [Personas & Jobs](../01-discovery/personas-and-jobs.md) |
| Q-002 | OPEN | 판매 단위가 SKU/수량인지 좌석·예약권 등인지, 캠페인 재고와 전역 재고는 어떤 관계인가? | Q-010 논의 뒤 | [Domain Rules](../03-domain/business-rules.md), [Personas](../01-discovery/personas-and-jobs.md) |
| Q-003 | OPEN | Fairness baseline: 서버가 자격 확인 후 대기열 입장 시 순번을 발급한다(D-020). 동일 논리 요청 retry는 기존 ticket/순번을 재사용(D-021), worker 재시작 뒤 순번을 보존(D-022). 영속화 원자성/idempotency-key 범위, 변경 payload, tie, 만료/재입장, 유형별 매핑과 bot 방어는 어떻게 정의할까? | baseline은 완료된 [Issue #24](https://github.com/Neo1228/ATLAS/issues/24)에 기록; Q-002 유형 논의와 함께 나머지 경계 결정 | [Business Rules](../03-domain/business-rules.md), [Invariants](../03-domain/invariants.md) |
| Q-004 | OPEN | 예약 TTL 만료와 결제 진행/성공이 경합하면 주문·재고·결제는 어떻게 수렴해야 할까? | Q-002 뒤 | [FR-002](../02-requirements/requirements-index.md), [Experiments](../08-experiments/experiment-registry.md) |
| Q-005 | OPEN | Authorization/Capture 시점, 재시도, 결제 UNKNOWN 처리와 환불 정책은 무엇인가? | Q-010 논의 뒤 | [FR-002](../02-requirements/requirements-index.md), [Experiments](../08-experiments/experiment-registry.md) |
| Q-006 | OPEN | 내부 정산 원장과 PSP 실제 지급의 경계, 수수료·주기·보류·사후 정정은 무엇인가? | PSP 제품/지역 조사 필요 | [FR-003](../02-requirements/requirements-index.md) |
| Q-007 | OPEN | 대상 지역·통화·세금·개인정보·보안/규제 범위 및 합성 데이터 정책은? | 테스트 계획 전 | [NFR-001](../02-requirements/requirements-index.md), [EXP-007](../08-experiments/experiment-registry.md) |
| Q-008 | OPEN | 합성 workload의 요청량/RPS, 동시성, burst/request mix, p95/p99, budget, SLO/RTO/RPO 및 배포 환경은? 수치 가정은 사용자 합의 전 TBD. Gate 2 profile → Gate 3 capacity model → Gate 4 synthetic validation ([Issue #20](https://github.com/Neo1228/ATLAS/issues/20); EXP-001/002/009). | Gate 2 NFR planning | [Requirements](../02-requirements/requirements-index.md), [Experiments](../08-experiments/experiment-registry.md) |
| Q-009 | OPEN | Java/Spring/DB/인프라 버전과 PayPal 제품별 API 가용 범위는? | 관련 ADR/구현 직전 공식 확인 | [ADR-0001](../06-decisions/adr/ADR-0001-architecture-style.md), [Reference Register](reference-register.md) |

## 기존 제약
장기 도메인 A/B/C 유지, PayPal Sandbox 우선, Fake PSP를 별도로 다룬다. 실결제·실개인정보·실지급은 초기 제외. `Modular Monolith First`는 Proposed 가설이지 Accepted 결정이 아니다.
