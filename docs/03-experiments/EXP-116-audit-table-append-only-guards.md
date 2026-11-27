# EXP-116: 추가 전용이라고 문서화된 감사·이벤트 테이블의 운영 변경 사용처를 세고, 사용처가 없는 곳에 DB 가드를 둔다

- 상태: ✓ 사전 등록(시간 측정 전에 커밋). 결과 절은 측정 뒤에 채운다.
- 날짜: 2026-11-27
- 관련: [#720](https://github.com/Neo1228/ATLAS/issues/720), [EXP-113](EXP-113-account-notification-delivery-and-audit-stream.md)(#718, 전수 조사에서 발견), [EXP-111](EXP-111-ledger-integrity-durability.md)(V7 가드와 비용 임계), [ADR-0100](../02-decisions/adr/ADR-0100-settlement-ledger-guards.md), [ADR-0105](../02-decisions/adr/ADR-0105-audit-table-append-only-guards.md), INV-006

## 문제 한 문장

요구사항과 ADR이 추가 전용(append-only)이라고 쓰는 감사·이벤트 테이블 가운데 일부는 DB가 UPDATE·DELETE·TRUNCATE를 막지 않아, 결함이나 수동 SQL이 감사 행을 고쳐도 알 수 없다.

## 측정 전 조사: 테이블별 운영 변경 사용처 (시간 측정 아님, 기준 `origin/main` 57e6ccd7)

대상은 문서(요구사항, ADR, 결정 로그, 운영 가이드)가 추가 전용·불변·감사 행이라고 쓰는 테이블 전부다. `docs/`에서 `append-only|추가 전용|immutable|감사 행`을 찾아 테이블에 대응시켰다. 독립 실행 모듈 `atlas-inventory-location`의 마이그레이션 4개에는 그런 서술도 트리거도 없다.

사용처는 운영 코드(`atlas-*/src/main/**`, 마이그레이션 제외)에서 센다. 이 저장소는 JPA 엔터티가 0개(`@Entity|@Table(` 0건)라 SQL 문자열이 유일한 쓰기 경로다. 문자열이 여러 줄에 걸친 경우도 세려고 파일 전체에 정규식 `\b(UPDATE|DELETE\s+FROM|TRUNCATE(\s+TABLE)?)\s+(ONLY\s+)?<테이블>\b`를 적용했고, 한 줄 검색 `git grep -hPi '<같은 정규식>' -- 'atlas-*/src/main/**' ':!**/db/migration/**' | wc -l`과 16개 테이블 모두 같은 수가 나왔다. `INSERT ... ON CONFLICT ... DO UPDATE`(upsert)는 운영 코드에 13줄 있고 모두 대상 밖 테이블(`order_inventory_event_cursor`, `seller_payout_profile` 등)이다. 대상 테이블을 부모로 두는 `ON DELETE|UPDATE CASCADE|SET NULL` 외래 키는 0개다(마이그레이션의 21개 모두 다른 테이블). 대상 테이블에 `FOR UPDATE|SHARE`를 거는 운영 질의도 없다(대상 테이블을 읽는 파일의 잠금 4건은 `inventory_pool`, `paypal_webhook_retry`, `payment_recovery_job` 두 건에 건다).

| 테이블 | 추가 전용이라고 쓰는 문서 | V10까지의 가드: 행 UPDATE·DELETE / TRUNCATE | 운영 UPDATE | 운영 DELETE | 운영 TRUNCATE | 시험의 정리 DELETE | 처리 |
|---|---|---|---|---|---|---|---|
| `settlement_journal`, `settlement_entry`, `settlement_seller_allocation` | ADR-0011, ADR-0100 | V7 / V7 | 0 | 0 | 0 | `LedgerTestReset` 경유 | ✓ 그대로 |
| `domain_audit_event` | ADR-0102 | V9 / V9 | 0 | 0 | 0 | 0 | ✓ 런타임 역할 회수 목록에 더함(빠져 있었음) |
| `paypal_balance_report_run`, `_row`, `_disposition` | ADR-0033 | V1 / V1 | 0 | 0 | 0 | 0 | ✓ 런타임 역할 회수 목록에 더함 |
| `paypal_webhook_retry_attempt_history` | D-116("append-only 이력"), ADR-0032 | V1(`CLAIMED`에서 종결로 한 번만 허용) / V1 | 4 | 0 | 0 | 0 | ✓ 가드 그대로, 문서를 "한 번 종결되는 이력"으로 고침 |
| `inventory_transfer` | ADR-0017 | V1 / 없음 | 0 | 0 | 0 | 0 | ✓ V11 TRUNCATE 가드 |
| `paypal_webhook_retry_operator_audit` | ADR-0032(#279 감사 기반 재큐), ADR-0102 | V1 / 없음 | 0 | 0 | 0 | 0 | ✓ V11 TRUNCATE 가드 |
| `inventory_capacity_adjustment` | ADR-0014, INV-006 | 없음 / 없음 | 0 | 0 | 0 | 0 | ✓ V11 가드 |
| `seller_payout_profile_action` | ADR-0078, ADR-0092 | 없음(V5 INSERT 분리 트리거만) / 없음 | 0 | 0 | 0 | 0 | ✓ V11 가드 |
| `settlement_payout_operator_action` | ADR-0078, ADR-0092 | 없음(V5 INSERT 분리 트리거만) / 없음 | 0 | 0 | 0 | 2 | ✓ V11 가드 |
| `account_suspension_action` | ADR-0102(감사 원본) | 없음 / 없음 | 0 | 0 | 0 | 0 | ✓ V11 가드 |
| `payment_recovery_operator_action` | ADR-0102(감사 원본) | 없음 / 없음 | 0 | 0 | 0 | 4 | ✓ V11 가드 |
| `order_inventory_event` | ADR-0067("append-only 이벤트 로그") | 없음 / 없음 | 0 | 0 | 0 | 0 | ✓ V11 가드 |

- 운영 UPDATE 4건은 모두 `PayPalWebhookRetryRepository`의 시도 이력 종결(`outcome`·`finished_at` 기록)이고 V1 트리거가 허용하는 유일한 전이다. 그래서 이 테이블은 "추가 전용"이 아니라 "한 번 종결되는 이력"이며 문서를 고친다. 결정 로그 D-116은 소급 수정하지 않고 D-184와 ADR-0032에 바로잡는다.
- 시험의 정리 DELETE 6건(위 두 테이블)은 가드가 생기면 행이 있을 때 실패한다. 원장과 같은 시험 전용 경로(`session_replication_role = replica`)로 옮기고, 이름이 원장에 묶인 `LedgerTestReset`을 `AppendOnlyTestReset`으로 바꾼다.

## 대안

| 안 | 내용 | 판단 |
|---|---|---|
| A0 현재 | 문서만 추가 전용 | 결함·수동 SQL의 변경을 모른다(문제 그대로) |
| A1 V7과 같은 트리거 | 행 단위 `BEFORE UPDATE OR DELETE` + 문장 단위 `BEFORE TRUNCATE`, 슈퍼유저·소유 계정도 거절, 우회는 `DISABLE TRIGGER`·`session_replication_role` | 사용처 0건인 테이블에 채택 후보. 비용은 아래 H4·H5로 판정 |
| A2 런타임 역할 권한 회수만 | `scripts/db/runtime-role.sql`의 `REVOKE UPDATE, DELETE` 목록에 넣는다 | 옵트인(역할 분리를 켠 배포만)이고 소유 계정·수동 SQL은 막지 못한다. A1과 함께 둔다(ADR-0100과 같은 짝) |
| A3 문서 표현만 고침 | 가드 없이 "추가 전용"을 지운다 | 사용처가 있는 테이블(`paypal_webhook_retry_attempt_history`)에만 쓴다 |

## 측정 방법 (H4·H5)

- 환경: Colima VM 위 `postgres:18.6` 컨테이너 하나(라벨 `atlasexp=audit720`), 같은 컨테이너 안의 `pgbench`. V1~V11 SQL을 순서대로 적용한 DB.
- 칸: 표 2개(`order_inventory_event`, `inventory_capacity_adjustment`) × 동시성 c = 1, 8 × 암 2개.
- 암: 무 = 그 표의 V11 트리거 2개를 `DROP TRIGGER`(V11 이전 상태), 유 = V11과 같은 `CREATE TRIGGER`. 같은 DB·같은 창에서 run마다 바꾼다.
- `order_inventory_event`: 운영에서 가장 자주 쓰는 가드 대상이다(예약 workflow 상태 전이마다 V1 트리거가 한 행, 주문마다 여러 행. 재고 조정은 판매자 요청, 나머지는 운영자 조치). 문장은 V1 `order_inventory_record_workflow_event()`의 INSERT와 같은 꼴(`workflow_id`마다 `MAX(aggregate_version) + 1`, `workflow_id = gen_random_uuid()`).
- `inventory_capacity_adjustment`: EXP-113 H21과 같은 `CapacityAdjustmentRepository.record` CTE(`inventory_pool` UPDATE + 조정 INSERT, `sku_id = 'sku-' || :client_id`, 멱등 키 `gen_random_uuid()::text`, delta 1). 재고 풀 16행은 준비 단계에서 `session_replication_role = replica`로 넣는다(측정 문장은 기본 모드).
- run: 3초 데우기(버림) → `CHECKPOINT` → LSN 기록 → `pgbench -n -M prepared -c <c> -j <c> -T 10 --log` → LSN 기록. 지표는 로그 3열(마이크로초)의 중앙값·99번째 값, 처리량, run 앞뒤 LSN 차이 ÷ 처리 건수(WAL B/건).
- 순서: 라운드마다 홀수는 무 → 유, 짝수는 유 → 무. 라운드 안에서 표·c 순서는 고정.
- 깨끗한 run: run 시작 시 `load1 ≤ 12`, 다른 태그의 라벨 컨테이너가 있으면 run 직전 `docker stats --no-stream` 한 번에서 그 컨테이너들의 CPU가 모두 5% 미만, 실패 트랜잭션 0(첫 등록의 "다른 태그의 라벨 컨테이너 없음"을 첫 시간 측정 전에 이렇게 고쳤다. 다른 에이전트의 컨테이너가 쉬는 채로 떠 있어 부하가 아닌데도 모든 run을 버리게 되기 때문이다). 한 칸에서 같은 라운드의 두 암이 모두 깨끗해야 그 라운드 쌍을 쓴다. 깨끗한 쌍이 칸마다 3개 모일 때까지 최대 6라운드를 돈다. 6라운드 안에 3개가 안 모이면 그 칸은 판정하지 않고 그렇게 적는다.
- 잠금: 시간 측정 전체를 `measure-lock.sh acquire audit720 40` 아래에서 돌린다.
- 두 번째 시도(2026-11-28 00:30, 첫 시도 결과를 본 뒤 두 번째 시도 전에 커밋): 첫 시도(라운드 1~6, 2026-11-27 23:55~00:16, 48 run)는 모든 run이 `load1` 12.56~25.31로 깨끗한 run 조건을 못 지켜 판정에서 뺀다(다른 에이전트의 Gradle 시험과 부하 컨테이너가 같은 호스트에서 돌았다). 같은 규칙으로 라운드 7~12를 다시 돈다. 바뀌는 것은 두 가지뿐이다. run 앞에서 `load1 ≤ 12`가 될 때까지 최대 10분 기다린다(기다림은 판정 규칙을 바꾸지 않는다. 10분이 지나면 그대로 돌리고 오염으로 적는다). 잠금 만료를 넘기지 않도록 3라운드씩 잠금을 따로 잡는다. 그래도 칸마다 깨끗한 쌍이 3개 모이지 않으면 그 칸은 판정하지 않는다.

## 가설과 반증 조건

| ID | 가설 | 반증 조건 |
|---|---|---|
| H1 | 위 표에서 V11이 가드를 더하는 8개 테이블의 운영 UPDATE·DELETE·TRUNCATE·upsert 사용처는 0건이다 | 1건 이상(그 테이블은 가드 대신 문서를 고친다) |
| H2 | V11 뒤 8개 테이블 각각에 행이 있을 때 UPDATE·DELETE·TRUNCATE가 SQLSTATE `P0001`로 거절되고 행 수가 그대로다. 슈퍼유저 연결도 같다 | 하나라도 성공하거나 다른 SQLSTATE |
| H3 | 전체 `clean check`가 V11, 역할 회수 목록, 시험 정리 경로 변경만으로 통과한다 | 실패 1건 이상 |
| H4 | `order_inventory_event` INSERT의 p50 증가(유 − 무, 깨끗한 쌍의 암별 p50 중앙값)가 c = 1과 c = 8 모두 V7 임계 안이다: ≤ 25%이고 ≤ 0.3 ms. 행 트리거는 INSERT에서 발동하지 않으므로 차이는 0에 가깝다고 예상한다 | 어느 c에서든 25% 또는 0.3 ms 초과(그 표에서는 트리거를 빼고 A2만 둔다) |
| H5 | `inventory_capacity_adjustment` 조정 CTE도 H4와 같은 임계 안이다 | H4와 같음(그 표는 A2만) |
| H6 | 변이 4종이 각각 시험에서 잡힌다: M1 V11에서 한 테이블의 행 트리거 삭제, M2 한 테이블의 TRUNCATE 트리거 삭제, M3 역할 스크립트 회수 목록에서 한 테이블 삭제, M4 롤백 스크립트에서 `DROP TRIGGER` 한 줄 삭제 | 변이를 넣고도 해당 시험이 통과 |

p99와 WAL B/건은 맥락으로만 적고 판정에 쓰지 않는다(EXP-111과 같은 규칙).

## 결과

측정 뒤에 채운다.
