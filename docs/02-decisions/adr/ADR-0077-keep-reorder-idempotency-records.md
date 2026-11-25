# ADR-0077: Reorder idempotency record는 정리하지 않고 보존하며 재검토 조건을 둔다

- 상태: Accepted
- 날짜: 2026-11-25
- 관련 결정: [ADR-0008](ADR-0008-catalog-cart-order-boundaries.md), [D-063](../../00-governance/decision-log.md), [D-141](../../00-governance/decision-log.md)
- 근거: [EXP-083](../../03-experiments/EXP-083-reorder-idempotency-retention.md), [Issue #620](https://github.com/Neo1228/ATLAS/issues/620)

## 문제

Reorder는 terminal Cart Order의 line을 Buyer의 Cart에 합산하고, 같은 `Idempotency-Key` 재시도가 다시 합산되지 않도록 `order_reorder_request`에 record를 남긴다([Q-012](../../01-requirements/requirements-index.md#assumptions-and-open-questions)). record를 언제까지 남길지, 정리 job이 필요한지가 정해지지 않았다. 요구사항 색인은 storage 영향을 실측 전에 운영 규모로 해석하지 말라고 한다.

## 결정

정리하지 않는다. record를 영구 보존하고 코드는 바꾸지 않는다. 아래 재검토 조건 중 하나가 확인되면 다시 판단한다.

근거는 로컬 측정이다(PostgreSQL 18.6, 100만 행 이하, 합성 데이터. 조건과 한계는 [EXP-083](../../03-experiments/EXP-083-reorder-idempotency-retention.md)).

| 항목 | 값 |
| --- | --- |
| 행당 저장(heap + PK index), 100만 행 | typical(UUID 36자 key) 약 310 B, 상한 근처(252자 key) 약 1.58 KB |
| 요청당 증가(typical, 5,000건) | 316 B |
| 운영 코드가 이 테이블에 보내는 SQL | 2문장: PK 전체 일치 조회, `INSERT ... ON CONFLICT DO NOTHING`. seq scan 경로 없음 |
| 같은 record를 지운 뒤 같은 key로 재요청(통합 테스트 1회) | HTTP 200, 같은 SKU 수량 5에서 7로 중복 합산 |
| 코드베이스에서 `idempotency` 계열 key 컬럼을 가진 테이블 | `V1__baseline.sql` 74개 중 18개. 이 테이블을 뺀 17개의 행을 지우는 운영 코드는 찾지 못했다(운영 코드의 `DELETE`는 계정 MFA, 인증 throttle, wishlist, PayPal webhook retry뿐이고 `@Modifying` 쿼리는 UPDATE) |

- 정리를 넣으면 가장 큰 위험은 저장 공간이 아니라 정확성이다. Reorder의 효과는 Cart 수량 합산이고 합산은 멱등이 아니다. record가 사라진 뒤 같은 key로 재시도하면 오류 없이 다시 합산된다(위 측정). 정리하려면 "만료된 key 재시도는 거절한다"는 규칙이 따로 필요한데, 이 API에는 key 유효 기간 계약이 없고 재시도 창이 얼마나 필요한지 확인하지 못했다.
- 보존 비용은 작고 선형이다. 100만 행이어도 약 0.3 GB(typical)이며, 같은 정확성 위험을 지는 정리 job과 index를 더할 만큼의 이득이 측정되지 않았다.
- 행 수는 Order 수가 아니라 성공한 Reorder 요청 수로 늘어난다(같은 Order를 다른 key로 여러 번 Reorder하면 각각 행이 된다). 상한은 요청률이 정하며, 이 endpoint의 빈도 제한은 애플리케이션 코드에 없다.

### 재검토 조건

하나라도 해당하면 정리 정책(TTL 또는 cascade)을 다시 설계한다.

1. `pg_total_relation_size('order_reorder_request')`가 1 GiB에 도달한다. 이 임계값은 측정에서 나온 값이 아니라 점검 시점을 정하는 판단값이다. 측정 환산(추정)으로는 typical 약 350만 행, 상한 근처 key 약 68만 행에 해당한다.
2. Order 삭제 또는 Buyer 데이터 삭제 기능이 생긴다. 지금 FK `source_order_id`가 삭제를 막고, cascade에는 `source_order_id` index가 필요하다(100만 행에서 31.6 MB, +10.2%).
3. `Idempotency-Key` 유효 기간이 API 계약으로 정해진다.
4. 같은 key 재시도가 Cart를 바꾸지 않는 방식으로 Reorder 효과가 바뀌어 record 없이도 중복 합산이 일어나지 않게 된다. 이때는 정리가 정확성 위험 없이 가능하다.

확인 쿼리(읽기 전용): `SELECT pg_total_relation_size('order_reorder_request'), (SELECT count(*) FROM order_reorder_request);`

## 대안과 버린 이유

| 대안 | 버린 이유 |
| --- | --- |
| TTL 정리 job(`created_at` 기준 배치 삭제) | `created_at` index(+22.5 B/행, +7.3%)가 없으면 500행 배치가 seq scan(100만 행에서 9,181 page)이고, 있으면 index를 하나 더 유지한다. 새 worker와 설정 3종(보존 기간, 배치 크기, 주기)이 늘고, 정리 뒤 재시도가 중복 합산되는 실패(위 측정)를 막으려면 별도 만료 규칙이 필요하다. 이득은 행당 310 B 수준의 저장 공간이고 재시도 창 근거가 없다 |
| Order 보존에 맞춘 cascade | 삭제할 Order가 없어 지금은 실행되지 않는다. 구현하려면 FK에 `ON DELETE CASCADE`와 `source_order_id` index(+31.6 B/행)가 필요하고, 같은 Order의 재요청에서 Cart 중복 합산 위험이 생긴다. Order 삭제가 생길 때 함께 결정한다 |
| 영구 보존(채택) | 코드·스키마 변경이 없고 정리 뒤 중복 합산 경로가 없다. 대신 테이블이 줄지 않는다. 조건 1로 점검 시점을 둔다 |

## 결과와 한계

- 이 결정은 코드를 바꾸지 않는다. 100만 행 이하의 합성 데이터 측정이며 운영 규모, 지연, 장애 비용을 보장하지 않는다. 100만 행을 넘는 구간과 bloat, 백업 영향은 측정하지 않았다.
- 클라이언트의 실제 재시도 창, 프록시 계층의 빈도 제한 여부는 확인하지 못했다.
- 이 테이블의 증가를 알려 주는 알림이나 대시보드는 만들지 않았다. 재검토 조건 1은 사람이 위 쿼리로 확인해야 한다.

## 실패 경로와 되돌리기

- 정리 job이 없으므로 같은 key 재시도는 항상 record를 찾아 기존 Cart를 돌려준다(통합 테스트 `terminalCartOrderCanBeReorderedWithCurrentCatalogAndIdempotentCartMerge`가 같은 key 재시도에서 Cart 수량이 5로 유지됨을 확인한다).
- record를 수동으로 지우면 같은 key 재시도가 다시 합산된다. 운영 중 수동 삭제는 하지 않는다.
- 되돌리기: 이 PR은 문서만 바꾸므로 revert하면 Q-012가 미정으로 돌아간다. 이후 정리를 도입하면 지운 행은 복구할 수 없다. 도입 전에 재시도 창 계약과 만료 규칙을 먼저 정한다.

## 후속

- 재검토 조건 중 하나가 확인되면 정리 방식을 다시 정한다. 그 전에 같은 쿼리로 크기와 행 수를 다시 센다.
