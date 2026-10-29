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

Gate 1에서 승인된 baseline, 제안된 규칙, 남은 미결 경계를 구분한다. 예시는 가상 시나리오이며 구현/실험 결과가 아니다. queue admission, ticket, idempotency key 같은 용어는 [Glossary](../01-discovery/glossary.md)에 설명했다.

| ID | 규칙 | 상태 | 적용 범위 / 미정 경계 |
|---|---|---|---|
| BR-001 | 같은 대기열에서는 서버 순번이 작은 유효 요청에 먼저 기회를 준다. | Confirmed baseline (D-019) | 발급은 자격 확인 뒤 대기열 입장 시, 재시도는 기존 ticket 재사용, 재시작은 발급 순서 보존 (D-020~D-022). 저장·재입장·동순위·bot·판매 유형별 정책은 [Q-003](../00-governance/assumptions-and-open-questions.md)에서 미정; 기준은 완료된 [Issue #24](https://github.com/Neo1228/ATLAS/issues/24)에 기록. |
| BR-002 | 판매자는 정해진 판매 가능량을 넘겨 확정하지 않기를 원한다. | Proposed (D-017) | 판매 단위, 캠페인/전역 재고, 재입고·조정·취소·반품 방식은 [Q-002](../00-governance/assumptions-and-open-questions.md)에서 미정. |

## 기준 타임라인 — 합성 시나리오
1. 판매자가 제한된 판매 가능량을 가진 캠페인을 연다. 수량/상품 유형과 판매 정책은 아직 정하지 않는다.
2. 자격 확인을 통과한 요청이 대기열에 입장할 때 서버가 순번을 발급한다. 순번과 대기 상태를 함께 저장하는 방법은 미정이다.
3. 요청 수가 남은 재고보다 많을 때, 대기 순서와 재고/예약 결과가 모순되지 않는지 검증한다. 예약/확정 처리 절차는 아직 정하지 않았다.
4. 같은 논리 요청의 재시도는 기존 대기표(ticket)와 순번을 재사용한다. 서비스/작업 처리기(worker) 재시작 뒤 발급된 순서는 유지한다. 저장 키, 요청 내용 변경, 만료·취소·재입장 방식은 미정이다.

## 대기열 처리 기준 (Queue lifecycle)
아래 도식은 승인된 D-019~D-022만 나타낸다. 저장 방식, 원자성, 요청 식별 키 범위, 만료/재입장, 동순위, 자동화 봇 방어와 판매 유형별 차이는 아직 정하지 않았으며, 이 도식은 구현 설계가 아니다.

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

이 도식은 대기 순서 기준만 설명한다. 미정인 운영·보안 정책은 [Q-003](../00-governance/assumptions-and-open-questions.md)에 남아 있다.

이 타임라인은 정책 결정이나 성능 결과가 아니다. 관련 불변조건은 [Invariants](invariants.md), 검증 후보는 [Experiment Registry](../08-experiments/experiment-registry.md)를 참조한다.
