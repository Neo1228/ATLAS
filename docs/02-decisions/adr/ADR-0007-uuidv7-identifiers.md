---
id: ADR-0007
title: UUIDv7 식별자
status: Accepted
owner: Project Owner
date: 2026-11-03
links:
  decisions: [D-056]
  experiments: [EXP-014]
  references: [REF-104, REF-105, REF-106, REF-107]
---
# ADR-0007: UUIDv7 식별자

- 상태: ✓ 확정
- 날짜: 2026-11-03
- 결정 ID: D-056

## 맥락

EXP-014는 격리된 로컬 PostgreSQL 18.6에서 전략별 부모 100,000개와 자식 200,000개로 삽입 및 집합 기반 조회를 각각 5회 측정했습니다.
- UUIDv7 부모 PK 인덱스는 UUIDv4보다 27.9% 작았습니다(3,178,496 대 4,407,296 bytes).
- 부모 삽입 p50은 거의 같았습니다(211.49 대 212.64 ms).
- 합산 삽입 rows/s p50도 거의 같았습니다(183,503.1 대 184,479.5).
- BIGINT+UUIDv7 조합은 UUID만 쓴 방식보다 전체 저장 공간·처리량에서 뚜렷한 이점을 보이지 않았습니다.
- ID 생성과 commit 시간은 측정에 포함하지 않았습니다.

## 결정

Seller, Campaign, Admission Ticket, PurchaseOrder의 primary key는 Hibernate UUIDv7로 생성한다. PaymentAttempt의 primary key는 `order_id`이며 UUIDv7 Order ID를 그대로 참조한다. 별도 unique non-PK `attempt_id`는 기존 UUIDv4 provider identifier로 유지한다. UUID 컬럼/API/FK 형식과 `attempt_id` contract를 유지하며 BIGINT 내부 ID·별도 public ID·외부 generator dependency·handwritten generator는 추가하지 않는다.

## 근거

선택은 로컬 측정에서 확인된 UUIDv7 PK index 크기 이점, 거의 동일한 insert 결과, BIGINT hybrid의 유의미한 측정상 이점 부재를 함께 고려한 소유자 결정이다. 이는 production 성능 우위의 증명이 아니다.

## 결과와 한계

Hibernate 7.4.13.Final의 UUID `VERSION_7` generation style은 `@Incubating`이다. Full Testcontainers/PostgreSQL suite에서 Seller, Campaign, Ticket, Order 및 PaymentAttempt `order_id` primary key의 version 7을 검증했다. PaymentAttempt의 별도 unique non-PK `attempt_id`는 UUIDv4로 유지한다.
- EXP-014는 생성 비용을 제외했으며, 측정은 단일 세션의 작은 합성 workload다. Raw timing 편차와 같은 host의 Docker workload에 따른 resource contention 불확실성이 있다.
- Set-based 조회는 parent sequential scan을 사용해 point lookup latency를 입증하지 않는다.
- 동시 부하, 큰 working set, WAL/checkpoint 지속 부하 및 실제 application ID-generation 비용은 측정하지 않았다.
- 별도 검증 전까지 production 성능·용량 주장을 하지 않는다.

## 관련 기록

- 결정 및 실험: [D-056](../../00-governance/decision-log.md), [EXP-014](../../03-experiments/EXP-014-id-strategy-comparison.md)
- 참고: [REF-104~107](../../00-governance/reference-register.md)
