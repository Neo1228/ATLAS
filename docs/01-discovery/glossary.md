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
| Tenant | 판매사/조직 단위의 데이터·권한 경계. 계정 모델 ? OPEN. | ○ Draft |
| Campaign | 판매 기간과 조건이 있는 한정 판매 단위. 재고 연결 ? OPEN. | ○ Draft |
| Reservation | 주문 처리를 위해 가용 재고를 임시 점유. TTL·결제 경합 ? OPEN. | ○ Draft |
| Order Intent | 구매 요청을 나타내는 내부 기록. 생성/확정 상태 ? OPEN. | △ Proposed |
| PSP (Payment Service Provider) | 외부 결제 제공자. PSP 상태와 ATLAS 주문 상태는 별도 관리. | ○ Draft |
| Fake PSP | 응답 유실/지연을 재현하는 테스트용 제공자. PayPal 장애 증거 아님. | ✓ 범위 확정 |
| Webhook | 외부 시스템이 변경 사항을 ATLAS로 보내는 HTTP 알림. | ○ Draft |
| Authorization / Capture | Authorization: 금액 승인. Capture: 승인 금액 청구. 연동 방식 ? OPEN. | ? OPEN |
| Reconciliation | ATLAS 기록과 PSP 기록을 대조하고 차이를 추적. | ○ Draft |
| Settlement Ledger | 내부 정산 계산·정정·감사 기록. 실제 자금 이동/지급과 별개. | ○ Draft |
| Idempotency | 같은 논리 요청의 재처리로 중복 효과가 생기지 않는 성질. | ○ Draft |
| Idempotency key | 같은 논리 요청을 식별하는 key. 범위/보존 기간 ? OPEN. | ? OPEN |
| TTL (Time to Live) | 상태가 유효한 기간. 만료 뒤 동작 ? OPEN. | ? OPEN |
| Queue admission | 자격 통과 요청을 queue에 등록. 현재 기준: 등록 시 server sequence 발급. | ✓ Baseline (D-020) |
| Queue ticket | queue에 등록된 논리 요청을 식별. 같은 요청 retry는 기존 ticket 재사용. | ✓ Baseline (D-021) |
| Sequence number | server가 발급하는 queue 순서. 작은 값이 먼저 기회를 얻는 가상 기준. | ✓ Baseline (D-019~D-022) |
| Worker | 백그라운드 작업을 처리하는 process. Restart 후 queue 순서 보존은 기준, 저장 방식은 ? OPEN. | ○ Draft |
| Synthetic workload | 시스템 테스트용 생성 요청; 실제 사용자 traffic 아님. | △ Proposed |
| Workload profile | 테스트 요청 구성, RPS, concurrency, burst를 정의. 값은 ? OPEN. | △ Proposed |
| Capacity model | workload profile과 infra 조건으로 처리 용량/병목 추정. 추정은 measurement 아님. | △ Proposed |
| RPS / p95 / p99 | 초당 요청 수와 latency의 95/99 percentile. 목표값 ? OPEN. | ? OPEN |
| SLO / RTO / RPO | 서비스 수준/복구 목표. 값과 범위 ? OPEN. | ? OPEN |

## 관련 문서
- 목표/역할: [Personas & Jobs](personas-and-jobs.md)
- 요구사항과 정책 근거: [Requirements Index](../02-requirements/requirements-index.md), [Assumptions & Open Questions](../00-governance/assumptions-and-open-questions.md), [Roadmap](../09-delivery/roadmap.md)
