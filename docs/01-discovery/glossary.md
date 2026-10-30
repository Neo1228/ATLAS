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

## 거래·재고

| 용어 | 설명 | ATLAS 상태 |
|---|---|---|
| Tenant | 판매사·조직별 데이터와 권한 경계. | Account model ? OPEN |
| Campaign | 판매 기간·조건을 묶는 한정 판매 단위. | 초기 시나리오는 campaign 소유 pool 1개 ([D-023](../00-governance/decision-log.md)); 전체 재고 연결 ? OPEN |
| SKU | 재고 추적용 상품/variant 식별 코드. 같은 SKU 수량은 초기 시나리오에서 서로 대체 가능. | 초기 시나리오만 ([D-023](../00-governance/decision-log.md)); 전체 catalog model ? OPEN |
| Inventory pool | campaign/product/location/channel 범위의 판매 재고 집합. | 초기: campaign별 독립 pool 1개 ([D-023](../00-governance/decision-log.md)); 다른 범위 ? OPEN |
| Reservation | PSP 요청 전 재고를 임시 점유. Capture success 후 claim으로 전환; authorization success만으로 claim하지 않음. | [D-025/D-026](../00-governance/decision-log.md); TTL/expiry/recovery ? OPEN |
| Inventory claim | Capture success 확인 후 hold를 판매 확정 재고로 전환. Authorization은 최종 결제 성공이 아님. | [D-026](../00-governance/decision-log.md); failure/cancel recovery ? OPEN |
| Order Intent | 구매 요청을 나타내는 내부 기록. | △ Proposed |

## 결제·transaction·저장

| 용어 | 설명 | ATLAS 상태 |
|---|---|---|
| PSP | 외부 결제 제공자. PSP 상태와 주문 상태를 별도로 관리. | ○ Draft |
| Fake PSP | 결제 응답 유실·지연 재현용 test provider. 실제 PayPal 장애의 증거는 아님. | ✓ 범위 확정 |
| Webhook | 외부 시스템이 상태 변경을 알리는 HTTP request. | ○ Draft |
| Authorization / Capture | Authorization은 구매자 자금을 hold; capture는 승인된 금액을 청구. 초기 흐름은 AUTHORIZE → CAPTURE; capture success 후 inventory claim. | [D-026](../00-governance/decision-log.md); provider endpoint/recovery contract ? OPEN |
| UNKNOWN payment outcome | 결제 operation 결과 미확정. 실패와 다름. 같은 attempt를 확인하기 전 새 attempt 금지. | [D-025/D-026](../00-governance/decision-log.md); provider별 retry/key contract ? OPEN |
| Buyer session | sale-open admission/FIFO부터 hold, checkout think time, AUTHORIZE 제출, payment outcome 확정까지의 합성 구매 여정 ([D-031](../00-governance/decision-log.md)). | 초기 범위 선택; workload values OPEN |
| Business transaction | 여러 API/DB transaction을 포함할 수 있는 사용자 흐름 단위. 초기 checkout TPS는 capture 성공 또는 확정된 non-payment까지 해결된 Buyer session 수. PENDING/UNKNOWN은 in-flight로 유지 ([D-031](../00-governance/decision-log.md)). | Metric scope 정의; target OPEN |
| Database transaction | DB 변경의 commit/rollback 단위. Isolation/locking은 DB별 의미 확인. | DB 미선택 |
| Conditional update | 조건이 맞을 때만 row를 갱신하는 SQL pattern. 동시성 보장은 DB semantics 확인 필요. | Reference |
| Pessimistic lock | 쓰기 전에 lock을 잡고 충돌 작업을 대기시킴. | Reference |
| Optimistic locking | version/condition 확인 후 갱신. 충돌 시 retry/failure 처리. | Reference |
| Reconciliation | ATLAS와 PSP 기록을 대조하고 차이를 추적. UNKNOWN은 같은 payment attempt로 확인 ([D-025](../00-governance/decision-log.md)). | ○ Draft; PSP-specific recovery ? OPEN |
| Settlement Ledger | 내부 정산·정정·audit 기록. 실제 자금 이동과 별개. | ○ Draft |
| Idempotency key | 같은 논리 요청을 식별해 중복 효과를 방지하는 key. Provider 지원 시 같은 payment attempt에서 재사용 ([D-025](../00-governance/decision-log.md)). | 범위·retention·provider support ? OPEN |
| TTL (Time to Live) | Inventory hold 유효 기간. Confirmed hold부터 initial AUTHORIZE 제출까지 10분; 만료 연장 없음 ([D-028~D-030](../00-governance/decision-log.md)). | Test-only; production TTL/completion SLO OPEN ([Q-004/Q-008](../00-governance/assumptions-and-open-questions.md)); provider/API deadline과 별개 |
| EXPIRED_PENDING_PAYMENT | Inventory TTL이 지났지만 PSP outcome이 UNKNOWN/PENDING. Same-attempt reconciliation 전까지 stock unavailable ([D-025/D-027](../00-governance/decision-log.md)). | 장기 미해결 escalation ? OPEN |
| IDOR | Object ID를 바꿔 다른 사용자/tenant의 resource에 권한 없이 접근하는 취약점. | Security test candidate |

## Queue
아래는 용어 정의이며 queue architecture 선택이 아님.

| 용어 | 설명 | ATLAS 상태 |
|---|---|---|
| Queue admission | 자격을 통과한 요청을 대기열에 등록. 초기 시나리오는 sale-open 후에만 admission/sequence 발급 ([D-020/D-024](../00-governance/decision-log.md)). | ✓ Baseline |
| Queue ticket | Queue entry 식별자. 같은 key·payload의 active retry는 기존 ticket/sequence 반환. Payload가 다르면 거절; expiry 후 재입장은 새 key ([D-021/D-034](../00-governance/decision-log.md)). | ✓ Baseline |
| Idempotency key | 한 논리적 queue-join 요청의 식별자. 같은 key·payload는 기존 결과 재사용; payload 변경은 거절 ([D-034](../00-governance/decision-log.md)). Scope·retention OPEN ([Q-003](../00-governance/assumptions-and-open-questions.md)). | 동작 기준 선택; 저장 방식·보존 기간 OPEN |
| Sequence number | Server 순번. Campaign queue 안에서 unique; 낮은 값 우선. Expiry 후 재입장은 새 sequence를 tail에 발급 ([D-019/D-034](../00-governance/decision-log.md)). | ✓ Baseline |
| Queue depth / backlog | 처리 대기 중인 작업 수. Queue age와 함께 해석. | Metric |
| Backpressure | Downstream 지연·한계에 맞춰 upstream 입력을 늦추거나 거절하는 흐름 제어. | Concept |

## 트래픽·성능·신뢰성
용어는 metric scope를 명확히 하기 위한 것. Tool·target·architecture 선택 아님.

| 용어 | 설명 | ATLAS 상태 |
|---|---|---|
| Synthetic workload | 테스트용으로 생성한 traffic; 실제 사용자 traffic과 다름. | △ Proposed |
| Workload profile | 선언된 test의 Buyer journey, request/transaction mix, offered rate, arrival model, payload, concurrency, duration. | [D-031~D-033](../00-governance/decision-log.md): full Buyer session, hybrid arrivals, 10k sessions/60m synthetic baseline, geometric 2×/4× stress, sessions/s primary, report-only. Production demand/SLO와 burst parameters OPEN |
| Ramp-up | 선언된 시간 동안 offered rate/concurrency를 점진적으로 높이는 방식. | Workload parameter |
| Burst | 기준보다 높은 offered rate를 짧게 발생시키는 부하. | Workload parameter |
| Steady state | workload와 측정 결과가 비교에 충분히 안정됐다고 선언한 구간. | Test interval |
| Open workload model | 작업 도착을 response completion과 독립적으로 예약해 offered arrival rate 유지. | Reference |
| Closed workload model | 이전 작업 완료 후 다음 작업 시작. 응답이 느리면 arrival rate 감소 가능. | Reference |
| Throughput | 단위 시간당 완료 작업량. 요청·transaction·query 중 단위 명시. | Metric |
| RPS | 초당 HTTP/API request 수. Offered rate와 성공 완료율 구분. | Metric |
| TPS | 초당 transaction 수. Business/DB transaction 범위 명시; RPS와 다름. | Metric |
| QPS | 초당 DB query/operation 수. Query 범위 명시; API RPS와 다름. | Metric |
| Offered load / arrival rate | Load generator가 보내도록 설정한 request/transaction rate. | Test input |
| Achieved throughput | 대상이 실제 완료한 작업량. Offered load와 별도 기록. | Result metric |
| Concurrency | 동시에 처리 중인 in-flight request/transaction 수. Rate와 다른 단위. | Metric |
| Latency | 시작부터 완료까지 걸린 시간. Queue wait 포함 여부와 경계 명시. | Metric |
| p95 / p99 | 관측 latency 중 95%/99%가 이 값 이하. Average는 tail latency를 숨길 수 있음. | Metric |
| Error rate | 실패 건수/전체 측정 건수. Failure 기준과 분모 명시. | Metric |
| Utilization / Saturation | Resource 사용률 / 처리 한계에 가까운 정도. CPU만으로 포화 판단 금지. | Resource metrics |
| Little's Law | 안정 상태의 평균 대기·처리 작업 수: L = λ × W (도착률 × 평균 체류시간). 같은 scope/단위 적용; capacity target 아님 ([REF-022](../00-governance/reference-register.md)). | Queueing relation |
| RED / USE | Service: Rate, Errors, Duration. Resource: Utilization, Saturation, Errors. 분류 방식이지 tool/architecture 선택 아님. | Reference |
| Capacity model | 합의 workload와 infra 제약으로 sustainable load·bottleneck 추정. 추정값은 측정값이 아님. | △ Proposed |
| SLO / RTO / RPO | Service level, recovery time, recovery point 목표. 범위·수치 OPEN. | ? OPEN |

## 관련 문서
- 목표·역할: [Personas & Jobs](personas-and-jobs.md)
- 요구사항·정책: [Requirements Index](../02-requirements/requirements-index.md), [Assumptions & Open Questions](../00-governance/assumptions-and-open-questions.md), [Roadmap](../09-delivery/roadmap.md)
