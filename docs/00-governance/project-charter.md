---
id: DOC-001
title: Project Charter
status: Draft
owner: Project Owner
updated: 2026-10-29
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
| A. Payment Reliability | Authorization/capture, webhook, retry, idempotency, refund, 불확실한 결제 상태, recovery |
| B. Flash Commerce | Tenant/seller, campaign, queue admission, reservation, order, 고동시성 |
| C. Settlement & Reconciliation | 거래 대사, fee, 내부 ledger, 정정, audit trail |

- 세 영역 모두 장기 범위. 마일스톤별 순차 진행.
- 우선 PSP: PayPal Sandbox. Fake PSP는 장애 반복 재현용이며 실제 PayPal 동작의 증거가 아님.

## Discovery 기준 — ✓ Confirmed
- 가상 시나리오 기반. 실제 사용자 인터뷰/데이터 아님.
- 핵심 시나리오: 한정 재고 경쟁, 공정한 대기열, overselling 방지.
- 공통 buyer/seller 목표는 선택됨. 판매 유형별 행동은 ? OPEN (D-010, D-015~D-017; Q-002/Q-010).

## 초기 제한 — ✓ Confirmed
- 실제 결제·고객 개인정보·판매사 지급 제외. Sandbox 및 합성/가명 데이터만 사용.
- 주문 상태와 PSP 상태 분리. 내부 정산과 실제 자금 이동 분리.
- Modular Monolith First는 △ Proposed ([ADR-0001](../06-decisions/adr/ADR-0001-architecture-style.md)).
- Gate 0 승인은 구현 허가 아님. Gate 2와 구현 진입 별도 승인 필요.

## 성공 기준 — Draft
- 정책/불변조건을 검증 가능한 시나리오와 연결.
- 장애·경합·복구를 재현 가능한 절차와 한계로 기록.
- 성능/recovery 주장은 환경을 명시한 반복 가능한 측정으로 뒷받침.
- SLO, RPS, p95/p99 목표는 ? OPEN. 합의·측정 전 가정 처리 ([Glossary](../01-discovery/glossary.md)).

## 열린 주제
사용자 조사, 판매 단위/유형, queue fairness, reservation/payment/refund, settlement/fees, region/currency/tax/regulation, privacy, capacity targets, stack/deployment — [Assumptions & Open Questions](assumptions-and-open-questions.md).
