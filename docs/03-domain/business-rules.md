---
id: DOC-014
title: Business Rules
status: Draft
owner: Project Owner
updated: 2026-10-29
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
| BR-001 | Queue 내 낮은 sequence 우선. Admission 후 발급 (D-020), retry ticket 재사용 (D-021), restart 후 순서 유지 (D-022). | ✓ Baseline (D-019) | Q-003: storage/key/payload/lifecycle/tie/bot/type policy ? OPEN. Issue #24에 기록. |
| BR-002 | 판매 가능량 초과 확정 금지. | △ Proposed (D-017) | 판매 단위·campaign/global inventory·조정/취소 ? OPEN (Q-002). |

## 기준 흐름 — 합성 scenario
1. Seller가 제한 재고의 campaign을 연다. 단위/정책은 미정.
2. 자격 통과 요청을 queue에 admission하고 server sequence 발급. 저장 방식은 미정.
3. 요청 수가 잔여 재고보다 많을 때 순서·예약 결과의 일관성 확인. 세부 protocol 미정.
4. 같은 논리 요청의 retry는 ticket/sequence 재사용. service/worker restart 후 발급 순서 유지.

다음 Mermaid는 D-019~D-022만 표시. 저장/만료/동순위/bot/유형별 정책은 ? OPEN이며 구현 설계 아님.

```mermaid
sequenceDiagram
    actor Buyer as 구매자
    participant API as 대기열 API
    participant Queue as 대기열 상태
    Buyer->>API: 구매 요청 제출
    API->>API: 자격 확인
    alt 자격 미달
        API-->>Buyer: 대기열 입장 거절
    else 자격 통과
        API->>Queue: 대기열 입장 및 서버 순번 발급
        Queue-->>API: 대기표와 순번
        API-->>Buyer: 대기표와 순번
        opt 같은 논리 요청 재시도
            Buyer->>API: 같은 요청 재시도
            API->>Queue: 기존 대기표 조회
            Queue-->>API: 기존 대기표와 순번
            API-->>Buyer: 기존 대기표와 순번
        end
    end
    Note over Queue: 서비스 재시작 뒤 발급 순서를 보존; 저장·복구 방식은 미정
```

도식은 queue ordering baseline만 설명한다. 미정 경계는 [Q-003](../00-governance/assumptions-and-open-questions.md). 이 흐름은 정책·성능 결과가 아니다. [Invariants](invariants.md), [Experiment Registry](../08-experiments/experiment-registry.md) 참조.
