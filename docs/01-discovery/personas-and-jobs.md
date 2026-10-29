---
id: DOC-013
title: Personas and Jobs to Be Done
status: Draft
owner: Project Owner
updated: 2026-10-29
links:
  requirements: [FR-001, NFR-002]
  adr: []
  experiments: [EXP-001, EXP-003]
---
# 가상 페르소나와 사용자 목표 (Jobs-to-be-Done)

## 범위와 근거 상태
이 문서는 Gate 1의 **가상 학습 시나리오**다. 실사용자 조사·인터뷰·거래에서 도출한 사실이 아니다. 공통 구매자/판매자 목표는 사용자가 선택했으며, 실제 행동과 판매 유형별 차이는 아직 정하지 않았다 ([D-005/D-007/D-015~D-017](../00-governance/decision-log.md); [Q-001/Q-010](../00-governance/assumptions-and-open-questions.md)).

## 공통 핵심 페르소나

| 역할 | 근거 상태 | 가상 목표 | 아직 미정인 점 |
|---|---|---|---|
| 구매자 (Buyer) | 사용자 선택; 가상 목표 | 한정 판매에서 공정한 구매 기회를 얻고 재고/예약 상태를 정확히 알고 싶다. | 자격 확인 뒤 대기열 입장 시 서버가 순번 발급; 같은 요청 재시도는 기존 대기표(ticket) 재사용; 서비스 재시작 뒤 순서 보존 (D-019~D-022). 요청 식별 키, 동순위, 만료·재입장, 판매 유형별 동작은 미정 (Q-002/Q-003/Q-010). |
| 판매자 (Seller) | 가상 목표 | 여러 유형의 한정 판매를 공정하게 운영하면서 초과판매를 막고 싶다. | 판매 단위/캠페인 재고 관계, 대기열 입장 조건과 유형별 운영 차이는 미정 (Q-002/Q-003/Q-010). |

### 구매자 목표 문장 (Job statement) — Draft
한정 판매에 참여할 때 공정한 구매 기회를 얻고, 재고를 예약할 수 있었는지 정확히 확인하고 싶다. 이는 가상 목표의 표현이지 실제 구매자의 발언이 아니다.

### 판매자 목표 문장 (Job statement) — Draft
한정 판매를 운영할 때 정한 판매 가능량을 넘지 않고, 구매자에게 공정한 기회를 제공하며, 예약 결과를 확인하고 싶다. 이는 가상 목표의 표현이지 실제 판매자의 발언이 아니다.

## 기준 시나리오와 실패 경계
- 가상 기준 가설: 여러 구매 요청이 제한된 캠페인 재고에 동시에 도달하고, 판매자는 초과판매 없이 공정한 결과를 원한다 (D-010).
- 검토할 실패 경계: 자격 통과 후 대기열 입장 시 순번 발급, 같은 요청의 재시도, 서비스 재시작이 겹치는 상황.
- `가용 수량(Available) + 예약 수량(Reserved) + 판매 수량(Sold) = 초기 재고(Initial Stock)`은 입고·조정이 없는 단순 모델에서만 성립하는 예시다. 정책이나 합격 기준으로 채택한 공식은 아니다.

## 판매 유형별 차이
공통 구매자/판매자 목표를 사용하고, 실제 차이가 확인되면 유형별 행동·정책을 별도 시나리오로 추가한다. 수량/SKU, 좌석/티켓 등은 [Q-002](../00-governance/assumptions-and-open-questions.md)에서 논의할 예시이며 확정 목록이나 장기 범위의 제한이 아니다 (D-006).

## 검증 및 추적
- 실제 사용자·판매자 조사나 시장 수요가 검증됐다고 표현하지 않는다.
Q-003의 기준은 자격 확인 뒤 대기열 입장 시 순번을 발급하고, 같은 논리 요청의 재시도와 서비스 재시작에서도 기존 ticket/순서를 유지하는 것이다 ([D-019~D-022](../00-governance/decision-log.md)). 저장 원자성, key 범위/보존 기간, 만료, 동순위, bot 방어, 판매 유형별 차이는 [Q-003](../00-governance/assumptions-and-open-questions.md)에서 미정이다. 기준 결정은 완료된 [Issue #24](https://github.com/Neo1228/ATLAS/issues/24)에 기록됐다.

## 관련 문서
- [Glossary](glossary.md), [Requirements Index](../02-requirements/requirements-index.md)
- [Business Rules](../03-domain/business-rules.md), [Invariants](../03-domain/invariants.md)
- [Assumptions & Open Questions](../00-governance/assumptions-and-open-questions.md)
- 연계 ID: [FR-001/NFR-002](../02-requirements/requirements-index.md), [EXP-001/003](../08-experiments/experiment-registry.md).
