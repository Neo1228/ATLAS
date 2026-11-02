---
id: DOC-001
title: Project Charter
status: Draft
owner: Project Owner
updated: 2026-11-02
links:
  requirements: []
  adr: [ADR-0001]
  experiments: []
---
# ATLAS 프로젝트 헌장

## 목적
- 제한 재고에 요청이 몰리는 상황에서 주문·예약·결제·환불·정산 정합성과 장애 복구를 설계·실험으로 검증하는 학습 프로젝트.
- 실제 운영 성과나 검증된 시장 수요로 표현하지 않음.

## 장기 범위 — ✓ Confirmed

| 영역 | 주요 내용 |
|---|---|
| A. 결제 신뢰성 (Payment Reliability) | Authorization/capture, webhook, retry, idempotency, refund, 불확실한 결제 상태와 recovery |
| B. 고동시성 한정판매 (Flash Commerce) | Tenant/seller, campaign, queue admission, reservation, order |
| C. 정산·대사 (Settlement & Reconciliation) | 거래 대사, fee, 내부 ledger, 정정, audit trail |

- 세 영역 모두 장기 범위. 마일스톤별 순차 진행.
- 우선 PSP: PayPal Sandbox. Fake PSP는 장애 재현 도구이며 실제 PayPal 동작의 증거가 아님.
- 장기 역량과 미결정 범위: [요구사항 인덱스의 장기 제품 역량 지도](../02-requirements/requirements-index.md). Gate 2의 single-SKU flow는 전체 범위가 아닌 첫 검증 시나리오.

## 사용자·도메인 탐색 기준 — ✓ Confirmed
- 합성 시나리오 사용. 실제 사용자 인터뷰·데이터 아님.
- 핵심 시나리오: 한정 재고 경쟁, 공정한 대기열, overselling 방지.
- 공통 buyer/seller 목표는 선택됨. 유형별 행동은 미정 ([D-010, D-015~D-017](decision-log.md); [Q-002/Q-010](assumptions-and-open-questions.md)).

## 초기 제한 — ✓ Confirmed
- 실제 결제·고객 개인정보·판매사 지급 제외. Sandbox와 합성/가명 데이터만 사용.
- 주문 상태와 PSP 상태 분리. 내부 정산과 실제 자금 이동 분리.
- Modular Monolith First와 초기 module/data 방향 승인 ([ADR-0001](../06-decisions/adr/ADR-0001-architecture-style.md), [D-037~D-039](decision-log.md)). Physical schema·lock recovery·구현 세부는 Gate 3 설계 대상.
- Gate 2의 current Buyer journey 기준 승인. Runtime code 작성·배포는 별도 승인 대상.

## 성공 기준 — △ Proposed
- 정책·invariant를 검증 가능한 시나리오에 연결.
- 장애·경합·복구를 재현 절차와 한계까지 기록.
- 성능·복구 주장은 환경을 명시한 반복 측정으로 뒷받침.
- SLO, RPS, p95/p99 목표는 미정. 합의·측정 전까지 가정 ([용어집](../01-discovery/glossary.md), [Q-008](assumptions-and-open-questions.md)).

## 열린 주제
- 사용자 조사, 판매 단위·유형, 대기열 공정성.
- 예약·결제·환불, 정산·수수료.
- 지역·통화·세금·규제, 개인정보 보호.
- Java/Spring Boot/PostgreSQL 버전, infrastructure/deployment, PayPal product/API 범위 ([Q-009](assumptions-and-open-questions.md)).
- 세부 항목: [가정 및 미결정 사항](assumptions-and-open-questions.md).
