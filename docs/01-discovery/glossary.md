---
id: DOC-005
title: Glossary
status: Draft
owner: Project Owner
updated: 2026-10-30
links:
  requirements: []
  adr: []
  experiments: []
---
# 용어집

## Commerce

| Term | Meaning | ATLAS state |
|---|---|---|
| Tenant | 판매사/조직별 data·permission boundary. | Account model ? OPEN |
| Campaign | 판매 기간/조건이 있는 한정 판매 단위. | 초기 시나리오는 campaign 소유 pool 1개 ([D-023](../00-governance/decision-log.md)); 전체 재고 연결 방식 ? OPEN |
| SKU | 재고 추적에 쓰는 상품/variant 식별 코드. 초기 시나리오에서는 같은 SKU 수량을 서로 대체 가능한 단위로 취급. | 초기 시나리오만 ([D-023](../00-governance/decision-log.md)); 전체 catalog model ? OPEN |
| Inventory pool | 판매 가능 재고를 campaign/product/location/channel 범위로 묶은 집합. | 초기: campaign별 독립 pool 1개 ([D-023](../00-governance/decision-log.md)); 다른 범위 ? OPEN |
| Reservation | PSP request 전 inventory를 임시 점유. Capture success 확인 후 claim으로 전환; authorization success만으로 claim하지 않음. | [D-025/D-026](../00-governance/decision-log.md); TTL/expiry/recovery ? OPEN |
| Inventory claim | Capture success 확인 후 hold를 판매 확정 inventory로 전환. Authorization은 final payment success가 아님. | [D-026](../00-governance/decision-log.md); failure/cancel recovery ? OPEN |
| Order Intent | 구매 요청을 나타내는 내부 기록. | △ Proposed |

## Payment, transaction and storage

| Term | Meaning | ATLAS state |
|---|---|---|
| PSP | 외부 결제 제공자. PSP status와 주문 status는 별도 관리. | ○ Draft |
| Fake PSP | 결제 응답 유실/지연 재현용 test provider. 실제 PayPal 장애 증거 아님. | ✓ 범위 확정 |
| Webhook | 외부 시스템이 status 변경을 알리는 HTTP request. | ○ Draft |
| Authorization / Capture | Authorization은 buyer funds를 hold하고, capture는 승인된 funds를 청구. 초기 scenario는 `AUTHORIZE → CAPTURE`; capture success 확인 후 inventory claim. | [D-026](../00-governance/decision-log.md); provider endpoint/recovery contract ? OPEN |
| UNKNOWN payment outcome | Operation 결과가 미확정된 상태; 실패와 다름. 같은 attempt를 확인하기 전 새 attempt 금지. | [D-025/D-026](../00-governance/decision-log.md); provider별 retry/key contract ? OPEN |
| Business transaction | Checkout 등 사용자 흐름 단위. 여러 API request/DB transaction으로 구성 가능. | Metric scope는 test에서 지정 |
| Database transaction | DB operation의 commit/rollback 단위. Isolation/locking semantics는 DB별 차이. | DB 선택 없음 |
| Conditional update | 조건이 맞을 때만 row를 갱신하는 SQL pattern. 동시성 보장은 DB semantics 확인 필요. | Reference |
| Pessimistic lock | 쓰기 전에 lock 획득; 충돌 작업은 대기. | Reference |
| Optimistic locking | version/condition 확인 후 갱신; 충돌 시 retry/failure 처리. | Reference |
| Reconciliation | ATLAS와 PSP 기록을 대조하고 차이를 추적. UNKNOWN 결과는 동일 payment attempt로 확인 ([D-025](../00-governance/decision-log.md)). | ○ Draft; PSP-specific recovery ? OPEN |
| Settlement Ledger | 내부 정산·정정·audit 기록. 실제 자금 이동과 별개. | ○ Draft |
| Idempotency key | 같은 논리 요청을 식별해 중복 효과를 막는 key. [D-025](../00-governance/decision-log.md): provider가 지원하면 같은 payment attempt의 key 재사용. | Scope/retention/provider support ? OPEN |
| TTL (Time to Live) | 상태의 유효 기간. [D-027](../00-governance/decision-log.md): 만료 시 capture 상태 확인; success면 claim, 미캡처 active authorization이면 void. Confirmed non-payment와 void/expiry 확인 전 inventory release 금지. | TTL 수치와 복구 deadline ? OPEN |
| EXPIRED_PENDING_PAYMENT | Inventory TTL이 지났지만 PSP outcome이 UNKNOWN/PENDING. Same-attempt reconciliation 전까지 stock unavailable ([D-025/D-027](../00-governance/decision-log.md)). | 장기 미해결 escalation ? OPEN |
| IDOR | Object ID 직접 조작으로 타 사용자/tenant resource에 권한 없이 접근하는 취약점. | Security test candidate |

## Queue
이 항목은 용어 정의이며 queue architecture 선택 아님.

| Term | Meaning | ATLAS state |
|---|---|---|
| Queue admission | 자격을 통과한 요청을 대기열에 등록. 초기 시나리오에서는 sale open 후에만 admission/sequence 발급 ([D-020/D-024](../00-governance/decision-log.md)). | ✓ Baseline |
| Queue ticket | Queue entry를 식별. 같은 요청 retry 시 기존 ticket 재사용 ([D-021](../00-governance/decision-log.md)). | ✓ Baseline |
| Sequence number | Server 발급 queue 순서. 작은 값 우선이라는 가상 기준 ([D-019~D-022](../00-governance/decision-log.md)). | ✓ Baseline |
| Worker | Background job을 처리하는 process. Restart 후 queue 순서 보존은 기준 ([D-022](../00-governance/decision-log.md)); 저장 방식 ? OPEN. | ○ Draft |
| Queue depth / backlog | 처리 대기 중인 작업 수. Queue age와 함께 해석. | Metric |
| Backpressure | Downstream 지연/한계가 upstream 입력을 늦추거나 거절하는 흐름 제어. | Concept |

## Traffic, performance and reliability
정의는 metric scope를 명확히 하기 위한 용어. Tool, target, architecture 결정 아님.

| Term | Meaning | ATLAS state |
|---|---|---|
| Synthetic workload | 테스트용 생성 traffic; 실제 사용자 traffic 아님. | △ Proposed |
| Workload profile | Request/transaction mix, offered rate, arrival model, payload, concurrency, duration. | Values ? OPEN |
| Ramp-up | Offered rate/concurrency를 선언된 시간 구간에 점진적으로 높이는 방식. | Workload parameter |
| Burst | 기준 부하보다 높은 offered rate를 짧게 발생시키는 profile. | Workload parameter |
| Steady state | workload와 측정 동작이 비교 목적에 충분히 안정된다고 선언한 구간. | Test interval |
| Open workload model | 작업 도착을 response completion과 독립 예약. Offered arrival rate 유지. | Reference |
| Closed workload model | 이전 작업 완료 후 다음 작업 시작. 응답 지연 시 arrival rate 감소 가능. | Reference |
| Throughput | 단위 시간당 완료 작업량. Unit(요청/transaction/query) 명시. | Metric |
| RPS | 초당 HTTP/API request 수. Offered인지 successful completion인지 구분. | Metric |
| TPS | 초당 transaction 수. Business/DB transaction scope 명시; RPS와 동치 아님. | Metric |
| QPS | 초당 DB query/operation 수. Query 단위 명시; API RPS와 동치 아님. | Metric |
| Offered load / arrival rate | Load generator가 보내도록 설정한 request/transaction rate. | Test input |
| Achieved throughput | 대상이 실제 완료한 작업량. Offered load와 별도 기록. | Result metric |
| Concurrency | 동시에 처리 중인 in-flight request/transaction 수. Rate와 다른 단위. | Metric |
| Latency | 시작부터 완료까지 시간. Queue wait 포함 여부와 boundary 명시. | Metric |
| p95 / p99 | 관측 latency의 95%/99%가 이 값 이하. Average는 tail latency를 숨길 수 있음. | Metric |
| Error rate | 실패 건수/전체 측정 건수. Failure 기준·분모 명시. | Metric |
| Utilization / Saturation | Resource 사용률 / 처리 한계에 가까운 정도. CPU만으로 포화 판단 금지. | Resource metrics |
| Little's Law | 안정 상태 평균 system 내 작업 수(대기+처리): `L = λ × W` (도착률 × 평균 체류시간). 같은 scope/단위 적용; capacity target 아님 ([REF-022](../00-governance/reference-register.md)). | Queueing relation |
| RED / USE | Service: Rate, Errors, Duration. Resource: Utilization, Saturation, Errors. 분류 체계일 뿐 tool/architecture 선택 아님. | Reference |
| Capacity model | 합의된 workload와 infra constraints로 sustainable load/bottleneck 추정. 추정 ≠ measurement. | △ Proposed |
| SLO / RTO / RPO | Service level / recovery time / recovery point 목표. Scope/values ? OPEN. | ? OPEN |

## 관련 문서
- 목표/역할: [Personas & Jobs](personas-and-jobs.md)
- 요구사항/정책: [Requirements Index](../02-requirements/requirements-index.md), [Assumptions & Open Questions](../00-governance/assumptions-and-open-questions.md), [Roadmap](../09-delivery/roadmap.md)
