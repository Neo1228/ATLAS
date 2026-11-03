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

- 상태: Accepted
- 날짜: 2026-11-03
- 결정 ID: D-056

## 맥락

EXP-014는 격리된 로컬 PostgreSQL 18.6에서 전략별 100,000 parent와 200,000 child를 사용해 다섯 차례 insert 및 set-based lookup 측정을 수행했다. UUIDv7 parent PK index는 UUIDv4보다 27.9% 작았고(3,178,496 vs 4,407,296 bytes), parent insert p50은 거의 같았다(211.49 vs 212.64 ms). Combined insert rows/s p50도 거의 같았다(183,503.1 vs 184,479.5). BIGINT+UUIDv7 hybrid는 UUID-only보다 뚜렷한 총 storage/throughput 이점을 보이지 않았다. 측정 비용에는 ID 생성과 commit이 포함되지 않았다.

## 결정

Seller, Campaign, Admission Ticket, PurchaseOrder의 primary key를 Hibernate UUIDv7로 생성한다. PaymentAttempt의 primary key는 `order_id`이며 UUIDv7 Order ID를 그대로 참조한다. 별도 unique non-PK `attempt_id`는 기존 UUIDv4 provider identifier로 유지. UUID 컬럼/API/FK 형식과 `attempt_id` contract 유지; BIGINT 내부 ID·별도 public ID·외부 generator dependency·handwritten generator는 추가하지 않는다.

## 근거

선택은 로컬 측정에서 확인된 UUIDv7 PK index 크기 이점과 거의 동일한 insert 결과, 그리고 BIGINT hybrid의 유의미한 측정상 이점 부재를 함께 고려한 소유자 결정이다. 이는 production 성능 우위의 증명이 아니다.

## 결과와 한계

Hibernate 7.4.13.Final의 UUID `VERSION_7` generation style은 `@Incubating`이다. Full Testcontainers/PostgreSQL suite에서 Seller, Campaign, Ticket, Order 및 PaymentAttempt `order_id` primary key의 version 7을 검증했다. PaymentAttempt의 별도 unique non-PK `attempt_id`는 UUIDv4로 유지. EXP-014는 생성 비용을 제외했으며, 측정은 단일 세션의 작은 합성 workload로 raw timing 편차와 같은 host의 Docker workload에 따른 resource contention 불확실성이 있다. Set-based 조회는 parent sequential scan을 사용해 point lookup latency를 입증하지 않는다. 동시 부하, 큰 working set, WAL/checkpoint 지속 부하 및 실제 application ID-generation 비용은 측정하지 않았다. Production 성능·용량 주장은 별도 검증 전까지 금지.

## 관련 기록

- 결정 및 실험: [D-056](../../00-governance/decision-log.md), [EXP-014](../../08-experiments/EXP-014-id-strategy-comparison.md)
- 참고: [REF-104~107](../../00-governance/reference-register.md)
