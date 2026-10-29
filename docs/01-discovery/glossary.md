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
# 용어집

| Term | 뜻과 사용 경계 | 상태 |
|---|---|---|
| Tenant (테넌트) | 판매사 등 데이터·권한 경계로 격리되는 조직. 구체적 계정 모델은 미결정. | 초안 |
| Campaign (캠페인) | 판매 기간과 조건이 붙은 한정 판매 단위. 재고 연결 정책 미결정. | 초안 |
| Reservation (재고 예약) | 주문 처리를 위해 가용 재고를 임시 점유하는 상태/행위. TTL·결제 경합 정책 미결정. | 초안 |
| Order Intent (주문 의도) | 구매 요청을 표현하는 내부 주문 기록. 생성/확정 상태 정책 미결정. | 제안 용어 |
| PSP (Payment Service Provider) | 외부 결제 제공자. PSP의 결제 상태와 ATLAS의 주문 상태는 서로 별도로 추적한다. | 초안 |
| Fake PSP | 결제 응답 유실·지연 같은 장애를 반복 재현하는 시험용 가상 제공자. 실제 PayPal 장애의 증거는 아니다. | 범위 확정 |
| Webhook (웹훅) | 외부 시스템이 상태 변경을 알리기 위해 ATLAS에 보내는 HTTP 알림. | 초안 |
| Authorization / Capture | Authorization은 결제 금액 승인, Capture는 승인된 금액의 청구 처리다. 실제 연동 방식은 미정. | 미정 |
| Reconciliation (대사) | ATLAS 내부 기록과 PSP 기록을 비교해 차이를 찾고 해결 상태를 추적하는 과정. | 초안 |
| Settlement Ledger (정산 원장) | 내부 정산 계산·정정·감사 기록. PSP의 실제 자금 이동이나 판매사 지급과는 별개다. | 초안 |
| Idempotency (멱등성) | 같은 논리 요청을 다시 처리해도 중복 효과가 생기지 않는 성질. | 초안 |
| Idempotency key (멱등성 키) | 같은 논리 요청인지 식별하는 값. 범위와 보존 기간은 미정이다. | 미정 |
| TTL (Time to Live) | 예약 등 상태가 유효하게 유지되는 시간. 만료 후 동작은 미정이다. | 미정 |
| Queue admission (대기열 입장) | 자격을 확인한 요청을 대기열에 등록하는 일. 현재 기준에서는 등록 시 서버가 순번을 발급한다. | Confirmed baseline (D-020) |
| Queue ticket (대기열 등록 정보) | 대기열에 등록된 논리 요청을 식별한다. 같은 요청의 재시도는 기존 ticket을 재사용한다. | Confirmed baseline (D-021) |
| Sequence number (대기 순번) | 서버가 발급하는 순서 값. 현재 가상 기준에서는 작은 값이 먼저 기회를 얻는다. | Confirmed baseline (D-019~D-022) |
| Worker (작업 처리기) | 백그라운드에서 작업을 처리하는 프로세스. 재시작 후 순번 보존은 정했지만 저장 방식은 미정이다. | 초안 |
| Synthetic workload (합성 부하) | 실제 사용자 트래픽이 아닌 생성된 요청으로 시스템을 시험하는 부하. | 제안 |
| Workload profile (부하 프로필) | 시험할 요청 구성, 요청률, 동시성, 급증 조건을 정의한 입력값. 숫자는 아직 정하지 않았다. | 제안 |
| Capacity model (용량 모델) | 부하 프로필과 인프라 가정으로 처리 용량과 병목을 추정하는 모델. 추정은 실측 결과가 아니다. | 제안 |
| RPS / p95 / p99 | 초당 요청 수(Requests Per Second)와 응답시간의 95·99 백분위수. 목표값은 미정이다. | 미정 |
| SLO / RTO / RPO | 서비스 수준 목표, 복구 목표 시간(Recovery Time Objective), 복구 시점 목표(Recovery Point Objective). 값과 범위는 미정이다. | 미정 |

## 관련 문서
- 목표/역할: [Personas & Jobs](personas-and-jobs.md)
- 요구사항과 정책 근거: [Requirements Index](../02-requirements/requirements-index.md), [Assumptions & Open Questions](../00-governance/assumptions-and-open-questions.md), [Roadmap](../09-delivery/roadmap.md)
