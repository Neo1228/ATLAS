# ADR-0006: V6 새로 시작 migration

- 상태: ✓ 확정
- 날짜: 2026-11-03
- 담당: 프로젝트 책임자
- 관련 결정: [D-055](../../00-governance/decision-log.md). D-049의 Seller-SKU 소유권·공유 재고·전역 FIFO/K 유지.
- 관련 요구사항: [FR-004](../../01-requirements/requirements-index.md)
- 대체 범위: ADR-0005의 기존 데이터 전환 방법만. 나머지 도메인 결정은 유지.

## 배경

ADR-0005의 초기 데이터 전환안은 V1–V5 Campaign 데이터를 Seller-SKU 구조로 바꾸기 위해 소유권 mapping과 재고·K·대기열 통합이 필요하다고 봤습니다. 소유자는 기존 데이터를 보존하지 않고 새로 시작하기로 결정했습니다. 추정 mapping이나 자동 병합은 Campaign을 잘못 묶을 수 있고, 조용한 초기화는 데이터 손실을 일으킵니다.

## 결정

- V1–V5의 application table에 행이 하나라도 있으면 V6는 DDL 전에 실패합니다. 실패 시 기존 데이터와 schema를 변경하지 않습니다.
- 기존 application table이 비어 있으면 V1–V5 schema를 제거하고 현재 Seller-SKU schema를 새로 만듭니다.
- 기존 data mapping, aggregate, backfill, 보존은 하지 않습니다.
- 기존 local data가 필요하면 애플리케이션 밖에서 명시적으로 보관·초기화한 뒤 빈 DB에서 시작합니다. 앱은 기존 데이터를 자동 삭제하거나 조용히 초기화하지 않습니다.
- Seller-SKU 소유권, Campaign 간 공유 재고, 전역 FIFO/K, Campaign별 TTL·idempotency 범위 등 D-049의 도메인 결정은 유지합니다.

## 결과와 한계

기존 데이터가 든 DB에서는 사람이 별도로 archival/reset하지 않으면 V6 적용이 차단된다. 빈 V1–V5 DB만 fresh schema로 전환할 수 있다. 기존 데이터의 변환·복구는 이 migration 범위가 아니다.

## 추적

- [D-055](../../00-governance/decision-log.md)
- [FR-004](../../01-requirements/requirements-index.md), [Q-002/Q-003](../../01-requirements/requirements-index.md#assumptions-and-open-questions)
- [ADR-0005](ADR-0005-seller-sku-inventory-global-admission.md) — Seller-SKU 도메인 결정은 유지, 기존 데이터 전환 방식만 이 ADR로 대체
