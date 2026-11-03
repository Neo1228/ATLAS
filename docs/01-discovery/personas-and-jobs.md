---
id: DOC-013
title: Personas and Jobs to Be Done
status: Draft
owner: Project Owner
updated: 2026-11-02
links:
  requirements: [FR-001, NFR-002]
  adr: []
  experiments: [EXP-001, EXP-003]
---
# 가상 사용자 목표

## 근거와 한계
- ✓ 사용자가 선택한 Buyer/Seller 공통 목표.
- 실제 인터뷰·거래·시장 검증 결과 아님.
- 초기 합성 시나리오와 Gate 2 승인은 Campaign별 독립 pool 1개로 기록 ([D-023/D-036](../00-governance/decision-log.md)). Seller-SKU shared stock/global FIFO는 D-049에서 후속 선택, 구현은 [Issue #89](https://github.com/Neo1228/ATLAS/issues/89)·[PR #90](https://github.com/Neo1228/ATLAS/pull/90)에서 추적. Assigned-seat/time-slot 제외는 유지; 다른 장기 판매 유형은 ? OPEN ([Q-002/Q-010](../00-governance/assumptions-and-open-questions.md)).

## 공통 역할

| 역할 | 가상 목표 | 미정 사항 |
|---|---|---|
| Buyer | 공정한 구매 기회, 정확한 재고/예약 상태 | 자격 조건, 판매 유형별 흐름 ([Q-002/Q-010](../00-governance/assumptions-and-open-questions.md)) |
| Seller | 공정한 한정판매, overselling 방지 | 판매 단위, 재고 범위, 운영 차이 ([Q-002/Q-003/Q-010](../00-governance/assumptions-and-open-questions.md)) |

### Buyer job — Draft
한정 판매에서 공정한 기회와 정확한 예약 결과를 원함. 실제 사용자 발언 아님.

### Seller job — Draft
정한 판매 가능량 안에서 공정하게 판매하고 예약 결과를 확인하려는 가상 목표. 실제 판매자 발언 아님.

## 기준 시나리오
- 초기 범위: campaign 1개, 수량형 SKU/pool 1개.
- Sale open 후 동시 요청을 받아 queue 순서와 예약량이 pool capacity를 넘지 않는지 확인 ([D-010/D-019/D-023/D-024](../00-governance/decision-log.md)).
- 사용자 조사 결과가 아닌 학습용 선택 ([Business Rules](../03-domain/business-rules.md)).

## 실패·미정 경계
- Sale open 전 queue entry/sequence 발급 없음. Open 후 자격을 통과한 요청만 admission.
- 같은 논리 요청 retry는 ticket 재사용. Service restart 후에도 순서 유지 ([D-021/D-022](../00-governance/decision-log.md)).
- 장기 OPEN: production TTL·payment recovery 세부사항, 재고 조정·재입고, post-claim 취소·환불, 다른 판매 유형 ([Q-002/Q-004/Q-005/Q-010](../00-governance/assumptions-and-open-questions.md)). 초기 재고 계약과 범위는 [D-035](../00-governance/decision-log.md).
- 초기 pool: `available + reserved + claimed = initial capacity`, 각 수량 0 이상 ([INV-001](../03-domain/invariants.md), [D-035](../00-governance/decision-log.md)). 조정·재입고·post-claim 취소·환불은 범위 제외.

## 판매 유형별 차이

| 유형 | 재고 단위 | 핵심 충돌 | 추가 경계 |
|---|---|---|---|
| Assigned seat | 고유 좌석 ID, section/row/seat map | 같은 좌석의 동시 선택·hold | 좌석 map/label, 좌석별 hold, 접근·가격 tier |
| Time-slot admission | 날짜·시간 구간별 정원 | 같은 slot의 잔여 capacity 동시 차감 | slot 길이·정원, 도착 제한, 지각·미방문·walk-in·accessibility |

근거: [Eventbrite](../00-governance/reference-register.md) reserved-seat guide와 [TixTrack](../00-governance/reference-register.md) venue case는 개별 좌석·seat map·hold 흐름을 보여준다. [Museums Association](../00-governance/reference-register.md)와 [Techniquest case](../00-governance/reference-register.md)는 slot별 정원, 도착·미방문·점유 차이를 다룬다. 제품 문서·vendor 사례는 ATLAS backend 설계나 보편적 best practice의 근거가 아니다. 실제 사용자·시장 조사도 없음.

사용자 선택: Gate 2는 현재 quantity-SKU 기준으로 진행. Assigned-seat/time-slot은 추가하지 않고 [Q-010](../00-governance/assumptions-and-open-questions.md) OPEN으로 유지 ([D-036](../00-governance/decision-log.md), [Issue #64](https://github.com/Neo1228/ATLAS/issues/64), [PR #65](https://github.com/Neo1228/ATLAS/pull/65)).

## 추적
- [D-024](../00-governance/decision-log.md): sale open 전 admission 없음. Open 후 자격 통과 요청은 [D-019](../00-governance/decision-log.md) 기준 FIFO.
- Retry ticket 재사용과 restart 후 순서 유지: [D-021/D-022](../00-governance/decision-log.md).
- Queue sequence·active K: Gate 2 historical 기준은 campaign counter row ([D-039](../00-governance/decision-log.md)). 현재 선택은 Seller-SKU global FIFO/K ([D-049](../00-governance/decision-log.md)); Campaign idempotency와 ticket TTL은 별도 유지. Production K/TTL, lock recovery, bot/auth, 재고 조정·취소, 판매 유형은 미정 ([Q-002/Q-003/Q-007/Q-010](../00-governance/assumptions-and-open-questions.md); [Issue #89](https://github.com/Neo1228/ATLAS/issues/89), [PR #90](https://github.com/Neo1228/ATLAS/pull/90)).
- 연계: [Requirements](../02-requirements/requirements-index.md), [Business Rules](../03-domain/business-rules.md), [Invariants](../03-domain/invariants.md), [Experiments](../08-experiments/experiment-registry.md).
