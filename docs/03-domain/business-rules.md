---
id: DOC-014
title: Business Rules
status: Draft
owner: Project Owner
updated: 2026-10-30
links:
  requirements: [FR-001]
  adr: []
  experiments: [EXP-001]
---
# 비즈니스 규칙
문서 유형: 기준 자료

규칙 상태: `✓ Confirmed baseline`, `△ Proposed`, `? OPEN`. 내용은 합성 scenario이며 구현·실험 결과 아님. Queue admission, ticket, idempotency key 용어는 [Glossary](../01-discovery/glossary.md) 참조.

| ID | 규칙 | 상태 | 미정 경계 |
|---|---|---|---|
| BR-001 | 초기 scenario: sale open 후 자격 통과 요청만 admission ([D-020/D-024](../00-governance/decision-log.md)); 낮은 sequence 우선 ([D-019](../00-governance/decision-log.md)). D-034: active ticket에 same key·same payload는 기존 ticket/sequence 반환; payload가 바뀌면 거절하고 ticket 유지; expiry 후 새 key로 재입장하면 새 unique sequence를 tail에 발급. Restart 후 발급 순서 보존 ([D-022](../00-governance/decision-log.md)). | ✓ Baseline (D-019~D-024/D-034) | [Q-003](../00-governance/assumptions-and-open-questions.md): key scope/retention, payload 비교 필드, expiry trigger/duration, storage/atomicity, bot defense, 다른 판매 유형 OPEN. [Issues #24](https://github.com/Neo1228/ATLAS/issues/24), [#60](https://github.com/Neo1228/ATLAS/issues/60). |
| BR-002 | 초기 시나리오: 대체 가능한 수량형 SKU 1종과 campaign별 독립 pool ([D-023](../00-governance/decision-log.md)). 예약·판매 수량은 pool capacity 이내. | △ Proposed ([D-017/D-023](../00-governance/decision-log.md)) | [Q-002/Q-004](../00-governance/assumptions-and-open-questions.md): 재고 생명주기, 조정·취소·환불·결제 경합 ? OPEN. |
| BR-003 | [D-025~D-030](../00-governance/decision-log.md): PSP 요청 전 inventory hold; capture success 후 claim. Confirmed hold부터 initial AUTHORIZE 제출까지 10분 fixed window. Production TTL/SLO 미정. 만료 시 checkout 종료, 연장 없음. 제출된 PENDING/UNKNOWN은 D-027에 따라 같은 attempt를 reconcile; 결과 전 새 attempt 금지. | ✓ Baseline | Production TTL/completion SLO, 측정 분포, provider deadline, retry/key retention, escalation/compensation OPEN ([Q-004/Q-005/Q-008](../00-governance/assumptions-and-open-questions.md)); [Issue #48](https://github.com/Neo1228/ATLAS/issues/48). |

## 기준 흐름 — 합성 scenario
1. Seller가 campaign 하나에 수량형 SKU 1종과 독립 inventory pool을 설정. 실제 수량은 test profile에서 선택.
2. Sale open 전에는 queue admission이나 sequence 발급 없음 ([D-024](../00-governance/decision-log.md)).
3. Sale open 후 자격을 통과한 Buyer 요청만 admission. Server sequence 부여, 낮은 순서 우선 ([D-019/D-020](../00-governance/decision-log.md)).
4. Active ticket의 같은 key·payload 재시도는 기존 ticket/sequence 반환. Payload가 바뀌면 거절; 만료 후 재입장은 새 key와 새 순번 사용 ([D-021/D-034](../00-governance/decision-log.md)). Service/worker restart 후 순서 보존 ([D-022](../00-governance/decision-log.md)).
5. Inventory hold 확정 시 Buyer-facing timer 시작 ([D-028/D-029](../00-governance/decision-log.md)).
6. Buyer는 만료 전 initial AUTHORIZE 요청 제출. 미제출이면 checkout 종료, payment attempt 없음.
7. 만료 전 제출한 AUTHORIZE는 만료 뒤에도 resolve 가능. Capture/void가 PENDING/UNKNOWN이면 stock을 hold하고 같은 attempt를 reconcile; 새 attempt 금지 ([D-027](../00-governance/decision-log.md)).
8. Capture success 후 inventory claim. 그 외에는 non-payment와 authorization void/expiry가 확인된 뒤 release ([D-027](../00-governance/decision-log.md)).

```mermaid
sequenceDiagram
    actor Buyer as 구매자
    participant API as Campaign API
    participant Queue as 대기열 상태
    Buyer->>API: campaign 접근 요청
    API->>API: sale-open 상태 확인
    alt sale open 전
        API->>API: admission 생략, queue entry·sequence 미발급
    else sale open
        API->>API: 구매 자격 확인
        alt 자격 미달
            API->>API: admission 생략
        else 자격 통과
            API->>Queue: admission 후 server sequence 발급
            Queue-->>API: ticket과 sequence
            API-->>Buyer: ticket과 sequence
            opt active ticket의 same key 재시도
                Buyer->>API: key와 payload 재전송
                alt payload 일치
                    API->>Queue: ticket 조회
                    Queue-->>API: 같은 ticket·sequence
                    API-->>Buyer: 같은 ticket·sequence
                else payload 변경
                    API-->>Buyer: 요청 거절, 기존 ticket 유지
                end
            end
            opt 만료 뒤 재입장
                Buyer->>API: 새 key로 입장 요청
                API->>Queue: 새 admission, 고유 순번을 tail에 발급
                Queue-->>API: 새 ticket·sequence
                API-->>Buyer: 새 ticket·sequence
            end
        end
    end
    Note over Queue: 낮은 sequence 우선, campaign 안에서 unique. Restart 후 발급 순서 보존. Storage 방식 미정
```

Queue sequence diagram은 [D-019~D-024/D-034](../00-governance/decision-log.md) 기준. 남은 경계: [Q-003](../00-governance/assumptions-and-open-questions.md). [Issue #60](https://github.com/Neo1228/ATLAS/issues/60).

## 결제 중 재고 hold 흐름 — 초기 시나리오
[D-025~D-029](../00-governance/decision-log.md)에서 정한 inventory/payment 상태 경계. PSP API, DB transaction, 재고 저장 방식은 미정. TTL·recovery deadline도 OPEN ([Q-004/Q-005](../00-governance/assumptions-and-open-questions.md)).

```mermaid
stateDiagram-v2
    state "UNKNOWN operation" as Unknown
    state "Capture pending" as CapturePending
    state "Capture declined" as CaptureDeclined
    state "Authorization void pending" as VoidPending
    [*] --> Available
    Available --> Held: hold 확정; fixed timer 시작
    Held --> Available: AUTHORIZE 제출 전 TTL 만료
    Held --> Authorized: AUTHORIZE 확인
    Held --> Unknown: 만료 전 제출; 결과 불명
    Authorized --> CapturePending: capture 요청
    Authorized --> VoidPending: TTL/cancel; capture 미완료
    CapturePending --> Claimed: capture 완료
    CapturePending --> CaptureDeclined: 거절 확인
    CapturePending --> Unknown: 결과 불명
    CapturePending --> CapturePending: pending 지속; stock unavailable
    CaptureDeclined --> VoidPending: authorization 유효
    CaptureDeclined --> Available: non-payment 확정; active authorization 없음
    Unknown --> Claimed: capture 완료 확인
    Unknown --> CapturePending: capture pending 확인
    Unknown --> CaptureDeclined: capture 거절 확인
    Unknown --> VoidPending: 미캡처, authorization 유효
    Unknown --> Available: non-payment 확정; active authorization 없음
    Unknown --> Unknown: 같은 operation/attempt reconcile
    VoidPending --> Available: void/expiry와 non-payment 확인
    VoidPending --> Claimed: capture success 확인
    VoidPending --> Unknown: void 결과 불명
```

Buyer-facing timer는 initial AUTHORIZE 제출 시 끝남. D-027 reconciliation이나 PSP operation deadline에는 영향 없음. TTL·recovery deadline 수치 OPEN ([Q-004/Q-005/Q-008](../00-governance/assumptions-and-open-questions.md)).
