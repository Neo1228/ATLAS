# ADR-0006: V6 clean-start migration

- 상태: ✓ Accepted
- 날짜: 2026-11-03
- 담당자: Project Owner
- 관련 결정: [D-055](../../00-governance/decision-log.md); [D-049](../../00-governance/decision-log.md)의 Seller-SKU ownership·shared stock·global FIFO/K는 유지
- 관련 요구사항: [FR-004](../../02-requirements/requirements-index.md)
- supersedes: ADR-0005의 legacy-data migration 방법만; 나머지 도메인 결정은 유지

## 배경

V1–V5 Campaign 데이터를 Seller-SKU 구조로 변환하려면 소유권 mapping과 재고·K·queue 통합이 필요하다는 ADR-0005의 초기 migration 방법이 있었다. 소유자는 기존 데이터를 보존하지 않고 새로 시작하기로 결정했다. 추정 mapping이나 자동 병합은 잘못된 Campaign을 하나의 Seller-SKU로 결합할 수 있고, 조용한 초기화는 데이터를 잃게 한다.

## 결정

- V6는 V1–V5의 application table에 행이 하나라도 있으면 DDL 전에 실패한다. 실패 시 기존 데이터와 schema를 변경하지 않는다.
- 기존 application table이 비어 있으면 V1–V5 schema를 제거하고 Seller-SKU current schema를 새로 만든다.
- legacy data mapping, aggregate, backfill 또는 legacy preservation은 하지 않는다.
- 기존 local data가 필요한 경우 애플리케이션 밖에서 명시적으로 보관·초기화한 뒤 empty-start를 수행한다. 앱은 기존 데이터를 자동 삭제하거나 조용히 reset하지 않는다.
- Seller-SKU 소유권, Campaign 간 공유 재고, global FIFO/K, Campaign별 TTL·idempotency 범위 등 D-049의 도메인 결정을 유지한다.

## 결과

기존 데이터가 든 DB에서는 사람이 별도로 archival/reset하지 않으면 V6 적용이 차단된다. 빈 V1–V5 DB만 fresh schema로 전환 가능하다. 기존 데이터의 변환·복구는 이 migration 범위가 아니다.

## 추적

- [D-055](../../00-governance/decision-log.md)
- [FR-004](../../02-requirements/requirements-index.md), [Q-002/Q-003](../../02-requirements/requirements-index.md#assumptions-and-open-questions)
- [ADR-0005](ADR-0005-seller-sku-inventory-global-admission.md) — Seller-SKU 도메인 결정 유지, migration 방법은 본 ADR로 supersede
