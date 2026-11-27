# ADR-0105: 추가 전용이라고 문서화되고 운영 코드가 고치지 않는 감사·이벤트 테이블을 V7과 같은 트리거로 DB가 지킨다

- 상태: Accepted(사용자 승인 전 에이전트 제안; 승인 여부는 확인하지 못함)
- 날짜: 2026-11-28
- 관련 결정: [ADR-0100](ADR-0100-settlement-ledger-guards.md)(원장 V7 가드·런타임 역할 분리), [ADR-0102](ADR-0102-account-notification-outbox-and-audit-stream.md)(통합 감사 로그, 원본 가드 후속), [ADR-0014](ADR-0014-seller-inventory-capacity-adjustment.md)·INV-006(재고 조정 감사), [ADR-0067](ADR-0067-event-log-kafka-relay.md)(예약 이벤트 로그), [ADR-0032](ADR-0032-paypal-webhook-retry-queue.md)(웹훅 시도 이력), D-184
- 근거: [EXP-116](../../03-experiments/EXP-116-audit-table-append-only-guards.md), 이슈 [#720](https://github.com/Neo1228/ATLAS/issues/720)

## 문제

요구사항과 ADR이 추가 전용이라고 쓰는 감사·이벤트 테이블 16개 가운데 6개는 DB가 UPDATE·DELETE·TRUNCATE를 전혀 막지 않았고, 2개는 행 가드만 있고 TRUNCATE는 막지 않았다(EXP-116 조사 표). 결함이나 수동 SQL이 감사 행을 고치거나 지워도 남는 흔적이 없다. 반대로 문서가 "append-only"라고 쓴 `paypal_webhook_retry_attempt_history`는 운영 코드가 시도 행을 한 번 종결 UPDATE한다(4건).

## 결정

1. **운영 코드의 UPDATE·DELETE·TRUNCATE·upsert 사용처가 0건인 테이블은 V7과 같은 트리거로 막는다(마이그레이션 V11, 기본 켜짐).** 행 단위 `BEFORE UPDATE OR DELETE`와 문장 단위 `BEFORE TRUNCATE`가 SQLSTATE `P0001`로 거절한다.
   - 새로 가드: `inventory_capacity_adjustment`, `account_suspension_action`, `payment_recovery_operator_action`, `seller_payout_profile_action`, `settlement_payout_operator_action`, `order_inventory_event`. 함수 `reject_append_only_mutation()` 하나를 함께 쓰고 메시지는 `<테이블>_is_append-only`다.
   - TRUNCATE만 더함: `inventory_transfer`, `paypal_webhook_retry_operator_audit`(V1 행 가드의 함수 재사용, 메시지 그대로).
2. **사용처가 있는 테이블은 가드를 바꾸지 않고 문서를 고친다.** `paypal_webhook_retry_attempt_history`는 "한 번 종결되는 이력"이다(V1 트리거가 `CLAIMED` → 종결 전이만 허용). ADR-0032에 적고 D-116은 소급 수정하지 않는다.
3. **런타임 역할 회수 목록을 추가 전용 테이블 전부로 넓힌다.** `scripts/db/runtime-role.sql`의 `REVOKE UPDATE, DELETE`가 원장 3개에 더해 감사·이벤트 12개(위 8개, 빠져 있던 `domain_audit_event`, `paypal_balance_report_run`·`_row`·`_disposition`)를 회수한다. 역할 분리 자체는 ADR-0100대로 옵트인이다.
4. **시험이 이 테이블을 비우는 길은 하나로 둔다.** 원장용 `LedgerTestReset`을 `AppendOnlyTestReset`으로 바꾸고 정리 DELETE 6건을 그리로 옮긴다(한 연결의 `session_replication_role = replica`, 운영 코드는 쓰지 않음).

기본값 규칙: 가드는 운영 코드가 하지 않는 문장만 거절하므로 기본 켜짐이 서비스 흐름을 바꾸지 않는다. 비용(INSERT 경로)은 아래 근거와 같다.

## 근거 (EXP-116, 단일 호스트, PostgreSQL 18.6)

| 측정 | 값 |
|---|---|
| 운영 변경 사용처(H1) | 가드한 8개 테이블 UPDATE·DELETE·TRUNCATE·upsert 0건. `paypal_webhook_retry_attempt_history`만 UPDATE 4건(모두 종결). JPA 엔터티 0개, 대상 테이블로 향하는 CASCADE 외래 키 0개 |
| 거절(H2) | 슈퍼유저 연결에서 24문장(8개 × UPDATE·DELETE·TRUNCATE) 모두 SQLSTATE `P0001`, 행 수 그대로. V11 없이 돌린 같은 시험은 2개 중 2개 실패 |
| INSERT 비용(H4·H5, 깨끗한 쌍 5~6개) | `order_inventory_event` p50 c = 1 -0.6%(-0.001 ms), c = 8 +1.1%(+0.004 ms). 재고 조정 CTE c = 1 -1.0%(-0.002 ms), c = 8 -7.8%(-0.038 ms). 네 칸 모두 V7 임계(≤ 25%이고 ≤ 0.3 ms) 안. 행·TRUNCATE 트리거는 INSERT에서 발동하지 않는다 |
| 첫 측정 시도 | 48 run 모두 `load1` 12.56~25.31로 오염, 판정에서 뺌(다른 에이전트 부하와 겹침) |
| 변이(H6) | 4개(행 트리거 삭제, TRUNCATE 트리거 삭제, 역할 회수 목록 삭제, 롤백 `DROP` 삭제) 모두 시험이 잡음 |
| 기존 시험 | 정리 DELETE 6건을 `AppendOnlyTestReset`으로 옮김. 전체 검사 결과는 EXP-116 H3 |

## 대안과 기각 사유

| 대안 | 기각·채택 이유 |
|---|---|
| 아무것도 하지 않음 | 기각. 문서와 DB가 어긋난 채 감사 행 변경이 흔적 없이 가능하다. |
| 런타임 역할 권한 회수만 | 단독은 기각. 옵트인이라 역할 분리를 켜지 않은 배포에서는 보호가 없고, 소유 계정·수동 SQL은 막지 못한다. 트리거와 함께 둔다(ADR-0100과 같은 짝). |
| 문서의 "append-only"만 지움 | 사용처가 있는 1개(`paypal_webhook_retry_attempt_history`)에만 채택. 사용처 0건인 테이블은 INSERT 비용 차이가 V7 임계 안이고(H4·H5) 막지 않을 이유가 없다. |
| 테이블마다 전용 함수(V1·V7·V9식) | 기각. 메시지만 다른 같은 함수가 늘어난다. `TG_TABLE_NAME`으로 메시지를 만드는 함수 하나로 둔다. 기존 함수는 적용된 마이그레이션이라 고치지 않는다. |
| 감사 원본을 통합 로그(`domain_audit_event`)로 대체 | 범위 밖. 원본은 사유 원문·멱등 키를 갖고 로그는 일부러 빼므로 원본을 지킬 이유가 남는다. |

## 롤백

- V11: `scripts/db/rollback/V11__undo_audit_append_only_guards.sql`을 소유 계정으로 한 트랜잭션에서 실행한다. V11의 트리거 14개와 함수 1개만 지우고 V1의 행 가드와 데이터는 남기며, 마지막 줄이 `flyway_schema_history`의 V11 행을 지운다. 시험(`documentedRollbackScriptRemovesOnlyTheV11Guards`)이 이 동작을 확인한다.
- 역할 회수: 되돌릴 필요가 없다(앱이 UPDATE·DELETE하지 않는다). 되돌리려면 소유 계정으로 해당 테이블에 `GRANT UPDATE, DELETE ... TO atlas_runtime`.
- 정정이 필요한 감사 행: 소유 계정의 break-glass(한 트랜잭션에서 `DISABLE TRIGGER` → 수정 → `ENABLE TRIGGER`), 운영 가이드의 "감사·이벤트 테이블 추가 전용 가드" 절.

## 재검토 조건

| 대상 | 조건 |
|---|---|
| 새 사용처 | 운영 코드가 가드한 테이블에 UPDATE·DELETE를 하나라도 써야 하면(예: 감사 행 정정 API) 그 테이블은 V1 시도 이력처럼 허용 전이만 여는 트리거로 바꾸는 결정을 새로 한다 |
| 이벤트 로그 보관 | `order_inventory_event` 보관 정리를 DELETE로 해야 하면(ADR-0067 재검토 조건 2) break-glass가 아닌 보관 경로(파티션 분리·삭제, 또는 보관 기간 밖 행만 허용하는 트리거)를 함께 결정한다. 파티션 분리·삭제는 행·TRUNCATE 트리거를 발동하지 않는다 |
| 비용 | 운영에서 `order_inventory_event` 또는 재고 조정 INSERT p50이 가드 없는 때보다 25% 또는 0.3 ms 넘게 늘면 그 테이블은 트리거를 빼고 역할 회수만 둔다 |
| 역할 분리 | ADR-0100의 역할 분리 재검토 조건과 같다 |
