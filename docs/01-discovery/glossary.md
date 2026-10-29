---
id: DOC-005
title: Glossary
status: Draft
owner: Project Owner
updated: 2026-10-29
links:
  requirements: []
  adr: []
  experiments: []
---
# 도메인 용어집

| 용어 | 정의/사용 경계 | 상태 |
|---|---|---|
| Tenant (테넌트) | 판매사 등 데이터·권한 경계로 격리되는 조직. 구체적 계정 모델은 미결정. | 초안 |
| Campaign (캠페인) | 판매 기간과 조건이 붙은 한정 판매 단위. 재고 연결 정책 미결정. | 초안 |
| Reservation (재고 예약) | 주문 처리를 위해 가용 재고를 임시 점유하는 상태/행위. TTL·결제 경합 정책 미결정. | 초안 |
| Order Intent (주문 의도) | 구매 요청을 표현하는 내부 주문 기록. 생성/확정 상태 정책 미결정. | 제안 용어 |
| PSP (Payment Service Provider) | 결제 처리를 제공하는 외부 시스템. ATLAS 결제/주문 상태와 별도 사실을 가진다. | 초안 |
| Fake PSP | 응답 유실·지연 등 장애를 결정적으로 재현하는 테스트용 가상 제공자. 실제 PayPal 동작의 증거가 아니다. | 확정 범위 |
| Reconciliation (대사) | ATLAS 내부 기록과 외부 PSP 기록을 비교해 차이를 찾고 해결 상태를 추적하는 과정. | 초안 |
| Settlement Ledger (정산 원장) | 내부 정산 계산·정정·감사 기록. PSP 실제 자금 이동이나 판매사 지급과 동일하지 않다. | 초안 |
| Idempotency (멱등성) | 같은 논리적 요청 재수행이 중복 효과를 만들지 않도록 하는 성질. 키 범위/보존기간 미결정. | 초안 |

## 관련 문서
- 목표/역할: [Personas & Jobs](personas-and-jobs.md)
- 요구사항과 정책 근거: [Requirements Index](../02-requirements/requirements-index.md), [Assumptions & Open Questions](../00-governance/assumptions-and-open-questions.md)
