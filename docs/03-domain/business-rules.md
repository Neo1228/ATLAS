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

규칙 상태: `✓ Confirmed baseline`, `△ Proposed`, `? OPEN`. 가상 scenario 기준이며 구현/실험 결과 아님. queue admission, ticket, idempotency key는 [Glossary](../01-discovery/glossary.md) 참조.

| ID | 규칙 | 상태 | 미정 경계 |
|---|---|---|---|
| BR-001 | 초기 시나리오: sale open 후 자격 통과 요청만 대기열에 등록. 등록 시 sequence 발급 ([D-020](../00-governance/decision-log.md)), 낮은 순번 우선 ([D-019](../00-governance/decision-log.md)), 같은 요청 retry ticket 재사용 ([D-021](../00-governance/decision-log.md)), restart 후 순서 보존 ([D-022](../00-governance/decision-log.md)). | ✓ Baseline (D-019~D-024) | [Q-003](../00-governance/assumptions-and-open-questions.md): storage/key/payload/expiry/re-entry/tie/bot/다른 유형 정책 ? OPEN. [Issue #24](https://github.com/Neo1228/ATLAS/issues/24), [Issue #33](https://github.com/Neo1228/ATLAS/issues/33). |
| BR-002 | 초기 시나리오: 대체 가능한 수량형 SKU 1종과 campaign별 독립 pool ([D-023](../00-governance/decision-log.md)). 예약·판매 수량은 pool capacity 이내. | △ Proposed ([D-017/D-023](../00-governance/decision-log.md)) | [Q-002/Q-004](../00-governance/assumptions-and-open-questions.md): 재고 생명주기, 조정·취소·환불·결제 경합 ? OPEN. |
| BR-003 | [D-025](../00-governance/decision-log.md) initial lifecycle: PSP 요청 직전 hold; terminal success 후 claim, definitive failure 후 release. UNKNOWN은 같은 attempt를 reconcile하고 그전 새 attempt 금지; TTL expiry 중에도 `EXPIRED_PENDING_PAYMENT`로 pool unavailable. | ✓ Baseline | [Q-004/Q-005](../00-governance/assumptions-and-open-questions.md): TTL, auth/capture mapping, PSP key/retry contract, cancel, 장기 UNKNOWN 복구·보상 ? OPEN. [Issue #35](https://github.com/Neo1228/ATLAS/issues/35); [REF-023/027~029](../00-governance/reference-register.md). |

## 기준 흐름 — 합성 scenario
1. Seller가 한 campaign에 수량형 SKU 1종과 독립 재고 pool을 설정. 실제 수량은 실험 profile에서 결정.
2. Sale open 전에는 queue admission과 sequence 발급 없음 ([D-024](../00-governance/decision-log.md)).
3. Sale open 후 자격 통과 Buyer 요청을 admission하고 server sequence 발급. 낮은 sequence 우선 ([D-019/D-020](../00-governance/decision-log.md)).
4. 같은 논리 queue 요청 retry는 기존 ticket/sequence 재사용. service/worker restart 후 순서 유지 ([D-021/D-022](../00-governance/decision-log.md)).
5. Payment attempt 직전 해당 수량을 hold. PSP 결과가 terminal success면 claim, definitive failure면 release ([D-025](../00-governance/decision-log.md)).
6. Timeout/통신 단절은 UNKNOWN. 같은 attempt를 reconcile; 새 attempt 전에 원 결과 확인 ([Q-005](../00-governance/assumptions-and-open-questions.md)).
7. Hold TTL 중 결과가 UNKNOWN이면 `EXPIRED_PENDING_PAYMENT`; 재고는 reconciliation 전까지 unavailable.
8. TTL 수치, auth/capture 단계, 장기 미해결 UNKNOWN 복구/보상은 ? OPEN ([Q-004/Q-005](../00-governance/assumptions-and-open-questions.md)).

```mermaid
sequenceDiagram
    actor Buyer as 구매자
    participant API as Campaign API
    participant Queue as 대기열 상태
    Buyer->>API: Campaign access 요청
    API->>API: Sale-open 상태 확인
    alt Sale open 전
        API->>API: Admission 생략, queue entry와 sequence 없음
    else Sale open
        API->>API: 구매 자격 확인
        alt 자격 미달
            API->>API: 자격 미달로 admission 생략
        else 자격 통과
            API->>Queue: Admission 및 server sequence 발급
            Queue-->>API: Ticket과 sequence
            API-->>Buyer: Ticket과 sequence
            opt 같은 논리 요청 재시도
                Buyer->>API: 같은 요청 retry
                API->>Queue: 기존 ticket 조회
                Queue-->>API: 기존 ticket과 sequence
                API-->>Buyer: 기존 ticket과 sequence
            end
        end
    end
    Note over Queue: D-019 낮은 sequence 우선, restart 후 순서 보존. 저장·복구 방식 미정
```

첫 sequence diagram은 [D-019~D-024](../00-governance/decision-log.md)의 queue 기준. 잔여 경계 [Q-003](../00-governance/assumptions-and-open-questions.md). 다음 state diagram은 [D-025](../00-governance/decision-log.md)의 inventory/payment 상태만 나타내며 PSP API/DB 구현 아님. [Issue #33](https://github.com/Neo1228/ATLAS/issues/33), [Issue #35](https://github.com/Neo1228/ATLAS/issues/35) 참조.

## 결제 중 재고 hold 흐름 — 초기 시나리오
[D-025](../00-governance/decision-log.md)의 상태 기준만 표현. PSP API/DB transaction/재고 저장 방식을 정하지 않음. Terminal success의 auth/capture 단계와 TTL 값은 [Q-004/Q-005](../00-governance/assumptions-and-open-questions.md) OPEN.

```mermaid
stateDiagram-v2
    state "UNKNOWN payment outcome" as Unknown
    state "EXPIRED_PENDING_PAYMENT" as ExpiredPending
    [*] --> Available
    Available --> Held: hold before PSP request
    Held --> Claimed: terminal PSP success confirmed
    Held --> Available: definitive PSP failure
    Held --> Unknown: timeout or ambiguous response
    Unknown --> Claimed: same attempt reconciled success
    Unknown --> Available: same attempt reconciled failure
    Unknown --> Unknown: retry/reconcile same attempt only
    Unknown --> ExpiredPending: hold TTL elapses
    ExpiredPending --> Claimed: reconciliation confirms success
    ExpiredPending --> Available: definitive failure
    ExpiredPending --> ExpiredPending: still unknown, pool stays unavailable
```
