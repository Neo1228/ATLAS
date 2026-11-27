# EXP-116: 추가 전용이라고 문서화된 감사·이벤트 테이블의 운영 변경 사용처를 세고, 사용처가 없는 곳에 DB 가드를 둔다

- 상태: ✓ 완료(PostgreSQL 18.6, 단일 호스트, 비용은 칸마다 깨끗한 쌍 5~6개). 가설·반증 조건은 첫 시간 측정 전에 커밋했고(이 브랜치의 첫 커밋), 깨끗한 run 조건 수정과 두 번째 시도 규칙도 각각 그 측정 전에 커밋했다.
- 날짜: 2026-11-27
- 관련: [#720](https://github.com/Neo1228/ATLAS/issues/720), [EXP-113](EXP-113-account-notification-delivery-and-audit-stream.md)(#718, 전수 조사에서 발견), [EXP-111](EXP-111-ledger-integrity-durability.md)(V7 가드와 비용 임계), [ADR-0100](../02-decisions/adr/ADR-0100-settlement-ledger-guards.md), [ADR-0105](../02-decisions/adr/ADR-0105-audit-table-append-only-guards.md), D-184, INV-006

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
- 두 번째 시도(2026-11-28 00:30, 첫 시도 결과를 본 뒤 두 번째 시도 전에 커밋): 첫 시도(라운드 1~6, 2026-11-27 23:55~2026-11-28 00:11, 48 run)는 모든 run이 `load1` 12.56~25.31로 깨끗한 run 조건을 못 지켜 판정에서 뺀다(다른 에이전트의 Gradle 시험과 부하 컨테이너가 같은 호스트에서 돌았다). 같은 규칙으로 라운드 7~12를 다시 돈다. 바뀌는 것은 두 가지뿐이다. run 앞에서 `load1 ≤ 12`가 될 때까지 최대 10분 기다린다(기다림은 판정 규칙을 바꾸지 않는다. 10분이 지나면 그대로 돌리고 오염으로 적는다). 잠금 만료를 넘기지 않도록 3라운드씩 잠금을 따로 잡는다. 그래도 칸마다 깨끗한 쌍이 3개 모이지 않으면 그 칸은 판정하지 않는다.

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

### 조건

- 호스트: Apple M3 Pro(`Mac15,6`), Colima VM aarch64 8 vCPU·13 GiB, `postgres:18.6` 컨테이너 하나와 그 안의 `pgbench`. 측정 DB는 V1~V11을 순서대로 적용한 것이고 측정 뒤 컨테이너를 `-v`로 지웠다.
- 첫 시도(라운드 1~6, 2026-11-27 23:55~2026-11-28 00:11, 48 run): 모든 run이 `load1` 12.56~25.31(중앙값 19.70)로 깨끗한 run 조건을 못 지켰다. 다른 에이전트의 부하 컨테이너(CPU 최대 118.7%)와 Gradle 시험이 같은 호스트에서 돌았다. 등록한 대로 판정에서 뺐다. 참고로 같은 창의 암별 p50 중앙값은 이벤트 로그 c = 1 0.654 / 0.683 ms, c = 8 1.058 / 1.656 ms, 재고 조정 c = 1 0.795 / 0.703 ms, c = 8 2.467 / 2.849 ms이고 run 사이 범위가 차이보다 넓다(예: 재고 조정 c = 8 무 1.056~13.790 ms).
- 두 번째 시도(라운드 7~12, 2026-11-28 00:38~00:51, 48 run): 잠금 `audit720` 아래, 3라운드씩 두 번 잡았다. 잡을 때 `measure-lock.sh status`의 라벨 컨테이너는 `audit720` 하나였다. 48 run 중 46 run이 깨끗했고 2 run(라운드 11 이벤트 로그 c = 1 유, 라운드 12 이벤트 로그 c = 8 무)은 run 도중 뜬 다른 라벨 컨테이너의 CPU가 5.8%, 15.1%라 오염이다. 실패 트랜잭션은 96 run 모두 0이다.

### 쓰기 비용 (H4·H5, 두 번째 시도의 깨끗한 쌍)

암별 p50은 깨끗한 쌍의 run별 p50 중앙값이다. 차이는 유 − 무다.

| 표 | c | 깨끗한 쌍(라운드) | p50 무 / 유 ms | p50 차이 | p99 무 / 유 ms | tps 무 / 유 | WAL B/건 무 / 유 | load1 | 판정(≤ 25%이고 ≤ 0.3 ms) |
|---|---|---|---|---|---|---|---|---|---|
| `order_inventory_event` | 1 | 5(7, 8, 9, 10, 12) | 0.171 / 0.170 | -0.001 ms(-0.6%) | 0.778 / 0.839 | 4,763 / 4,662 | 2,495 / 2,879 | 5.31~7.91 | ✓ 임계 안 |
| `order_inventory_event` | 8 | 5(7, 8, 9, 10, 11) | 0.369 / 0.373 | +0.004 ms(+1.1%) | 1.926 / 1.590 | 16,901 / 17,272 | 972 / 1,068 | 5.34~9.90 | ✓ 임계 안 |
| `inventory_capacity_adjustment` | 1 | 6(7~12) | 0.207 / 0.205 | -0.002 ms(-1.0%) | 0.916 / 0.761 | 3,903 / 4,092 | 2,651 / 2,044 | 6.81~11.96 | ✓ 임계 안 |
| `inventory_capacity_adjustment` | 8 | 6(7~12) | 0.483 / 0.446 | -0.038 ms(-7.8%) | 3.818 / 2.167 | 11,485 / 14,769 | 2,346 / 1,825 | 6.02~11.11 | ✓ 임계 안 |

- 등록한 "깨끗한 쌍 3개가 모이면 멈춘다"를 엄격히 따라 라운드 7~9만 쓰면 p50 차이는 이벤트 로그 c = 1 +0.000 ms(+0.0%), c = 8 -0.041 ms(-11.1%), 재고 조정 c = 1 +0.002 ms(+1.1%), c = 8 -0.034 ms(-7.9%)로 판정이 같다. 라운드 10~12는 잠금을 3라운드씩 나눠 잡느라 이미 돌린 run이라 함께 적었다.
- 차이의 부호가 칸마다 갈리고 크기가 run 사이 흩어짐보다 작다. 예상대로 행 단위 `UPDATE OR DELETE` 트리거와 `TRUNCATE` 트리거는 INSERT에서 발동하지 않으므로 이 측정으로 구분되는 비용이 없다. p99와 WAL B/건은 등록대로 맥락이다(WAL은 run 앞 `CHECKPOINT` 뒤 첫 수정 페이지의 전체 페이지 쓰기가 섞여 run마다 흔들린다).
- 한계: DB 단독 쓰기(HTTP·앱 시간 없음), 단일 호스트, 10초 run이다. 재고 조정 문장은 앱의 바인드 변수 대신 상수를 썼다(`-M prepared`라 계획은 한 번 만든다).

run별 값(두 번째 시도, 같은 라운드의 두 암):

| 라운드 | 표 | c | 무 p50 / p99 ms | 유 p50 / p99 ms | 무 / 유 tps | load1(무 / 유) | 상태 |
|---|---|---|---|---|---|---|---|
| 7 | 이벤트 로그 | 1 | 0.150 / 0.587 | 0.145 / 0.590 | 5,403 / 5,839 | 5.31 / 5.35 | 깨끗 |
| 8 | 이벤트 로그 | 1 | 0.142 / 0.456 | 0.150 / 0.604 | 6,189 / 5,661 | 6.22 / 6.41 | 깨끗 |
| 9 | 이벤트 로그 | 1 | 0.171 / 0.808 | 0.284 / 1.683 | 4,678 / 2,612 | 7.31 / 7.91 | 깨끗 |
| 10 | 이벤트 로그 | 1 | 0.173 / 1.226 | 0.170 / 0.839 | 4,151 / 4,662 | 6.25 / 6.07 | 깨끗 |
| 11 | 이벤트 로그 | 1 | 0.242 / 1.381 | 0.176 / 0.699 | 3,020 / 4,768 | 9.07 / 10.78 | 오염(유 다른 컨테이너 CPU 5.8%) |
| 12 | 이벤트 로그 | 1 | 0.172 / 0.778 | 0.203 / 1.233 | 4,763 / 3,566 | 7.25 / 7.33 | 깨끗 |
| 7 | 이벤트 로그 | 8 | 0.340 / 1.851 | 0.373 / 2.670 | 18,077 / 15,217 | 5.34 / 5.84 | 깨끗 |
| 8 | 이벤트 로그 | 8 | 0.369 / 1.981 | 0.319 / 0.921 | 16,901 / 22,140 | 6.68 / 6.21 | 깨끗 |
| 9 | 이벤트 로그 | 8 | 0.491 / 1.870 | 0.328 / 1.218 | 14,310 / 20,617 | 7.41 / 8.70 | 깨끗 |
| 10 | 이벤트 로그 | 8 | 0.643 / 4.226 | 0.577 / 3.543 | 9,496 / 11,024 | 6.68 / 6.16 | 깨끗 |
| 11 | 이벤트 로그 | 8 | 0.362 / 1.926 | 0.393 / 1.590 | 17,906 / 17,272 | 9.90 / 8.35 | 깨끗 |
| 12 | 이벤트 로그 | 8 | 0.432 / 2.195 | 0.927 / 11.000 | 14,686 / 5,388 | 10.10 / 6.94 | 오염(무 다른 컨테이너 CPU 15.1%) |
| 7 | 재고 조정 | 1 | 0.183 / 0.802 | 0.185 / 0.713 | 4,397 / 4,301 | 7.24 / 7.29 | 깨끗 |
| 8 | 재고 조정 | 1 | 0.179 / 0.538 | 0.182 / 0.548 | 4,943 / 4,843 | 6.83 / 6.89 | 깨끗 |
| 9 | 재고 조정 | 1 | 0.224 / 1.031 | 0.208 / 0.658 | 3,410 / 4,149 | 7.51 / 7.50 | 깨끗 |
| 10 | 재고 조정 | 1 | 0.428 / 2.620 | 0.339 / 1.665 | 1,855 / 2,300 | 8.01 / 7.74 | 깨끗 |
| 11 | 재고 조정 | 1 | 0.190 / 0.600 | 0.206 / 0.809 | 4,633 / 4,035 | 7.58 / 6.81 | 깨끗 |
| 12 | 재고 조정 | 1 | 0.669 / 9.269 | 0.204 / 2.117 | 884 / 3,005 | 11.96 / 9.93 | 깨끗 |
| 7 | 재고 조정 | 8 | 0.380 / 1.540 | 0.386 / 1.435 | 17,969 / 17,781 | 6.69 / 6.49 | 깨끗 |
| 8 | 재고 조정 | 8 | 0.428 / 1.557 | 0.394 / 1.619 | 16,370 / 17,344 | 6.99 / 6.92 | 깨끗 |
| 9 | 재고 조정 | 8 | 0.708 / 4.330 | 0.452 / 2.716 | 8,859 / 13,596 | 6.38 / 6.02 | 깨끗 |
| 10 | 재고 조정 | 8 | 0.714 / 6.068 | 0.875 / 7.087 | 7,769 / 6,372 | 9.60 / 8.38 | 깨끗 |
| 11 | 재고 조정 | 8 | 0.506 / 3.306 | 0.439 / 1.478 | 11,894 / 15,942 | 6.17 / 6.72 | 깨끗 |
| 12 | 재고 조정 | 8 | 0.460 / 5.364 | 0.677 / 4.704 | 11,076 / 8,661 | 10.69 / 11.11 | 깨끗 |

### 거절 (H2)과 고치기 전 실패

- `AuditAppendOnlyGuardIntegrationTest`(새 시험 2개): 8개 테이블 각각에 한 행을 넣고 기본 모드의 슈퍼유저 연결에서 `UPDATE`·`DELETE`·`TRUNCATE` 24문장이 모두 SQLSTATE `P0001`로 거절되고 행 수가 1로 남는다. 롤백 스크립트는 한 트랜잭션 안에서 V11 테이블 6개의 DELETE를 허용하게 하고 V1 행 가드 2개는 남기며 TRUNCATE 가드는 지운다. 트랜잭션을 되돌리면 가드와 행이 그대로다.
- `SettlementLedgerRoleSeparationIntegrationTest`에 1개 추가: 역할 스크립트를 적용한 런타임 역할이 감사·이벤트 12개에 INSERT 권한은 있고 UPDATE 권한은 없으며 `DELETE`·`TRUNCATE`가 SQLSTATE `42501`이다. 같은 클래스의 기존 시험이 이 역할로 capture·환불·지급 요청·승인·확정을 끝까지 돌린다(이 흐름은 감사 테이블에 쓰지 않으므로 감사 INSERT 경로는 전체 검사의 기존 시험이 덮는다).
- 고치기 전: V11 파일을 빼고 역할 스크립트를 `origin/main` 판으로 돌린 같은 시험은 `AuditAppendOnlyGuardIntegrationTest` 2개 중 2개, `SettlementLedgerRoleSeparationIntegrationTest` 4개 중 1개(새 시험)가 실패했다(`Expecting code to raise a throwable`). 되돌린 뒤 세 클래스(`DomainAuditEventIntegrationTest` 포함) 8개 모두 통과했다.
- 운영 가이드의 확인 질의는 측정 DB에서 14행, 모두 `O`였다.

### 변이 확인 (H6)

변이마다 파일 하나를 `perl`로 고치고 해당 시험 클래스만 돌린 뒤 `git checkout`으로 되돌렸다.

| 변이 | 결과 |
|---|---|
| M1 V11에서 `order_inventory_event_append_only` 행 트리거 삭제 | `everyGuardedTableRejectsUpdateDeleteAndTruncate` 실패(2개 중 1개) |
| M2 V11에서 `inventory_transfer_no_truncate` 삭제 | 같은 시험 실패(2개 중 1개) |
| M3 역할 스크립트 회수 목록에서 `payment_recovery_operator_action` 삭제 | `runtimeRoleCannotUpdateOrDeleteAppendOnlyAuditTables` 실패(4개 중 1개). 첫 실행은 변이 정규식이 줄 끝 공백 때문에 파일을 바꾸지 못해(해시 같음) 버리고 정규식을 고쳐 다시 돌렸다 |
| M4 롤백 스크립트에서 `inventory_capacity_adjustment_append_only`의 `DROP TRIGGER` 삭제 | `documentedRollbackScriptRemovesOnlyTheV11Guards` 실패(2개 중 1개, 남은 트리거가 함수를 붙잡아 `DROP FUNCTION`이 실패) |

### 전체 검사 (H3)

`./gradlew clean check`(JDK 25, 잠금 `audit720-full` 아래, 커밋 29bc9994, 코드·마이그레이션은 이 PR의 마지막 커밋과 같고 뒤 커밋은 문서뿐): `BUILD SUCCESSFUL in 8m 50s`, 시험 결과 XML 203개, 시험 1,189개, 실패 0. `:atlas-application:test`는 실제로 돌았고 다른 모듈의 `test`는 바뀐 입력이 없어 빌드 캐시(`FROM-CACHE`)에서 왔다.

## 가설 판정 요약

| ID | 판정 | 근거 |
|---|---|---|
| H1 | ✓ 참 | 8개 테이블 운영 UPDATE·DELETE·TRUNCATE·upsert 0건(조사 표) |
| H2 | ✓ 참 | 24문장 `P0001`, 행 수 그대로, 고치기 전 실패 확인 |
| H3 | ✓ 참 | 전체 검사 시험 1,189개 실패 0(시험 정리 경로 6건만 옮김) |
| H4 | ✓ 참 | `order_inventory_event` p50 차이 c = 1 -0.6%(-0.001 ms), c = 8 +1.1%(+0.004 ms) |
| H5 | ✓ 참 | 재고 조정 p50 차이 c = 1 -1.0%(-0.002 ms), c = 8 -7.8%(-0.038 ms) |
| H6 | ✓ 참 | 변이 4개 모두 해당 시험이 잡음 |

## 주장 표

| 주장 | 증거 | 확신도 |
|---|---|---|
| 문서가 추가 전용이라 쓰는 테이블 중 8개는 운영 코드가 고치지 않는다 | 정규식 전수 검색과 `git grep` 교차 확인 0건, JPA 0개, CASCADE 외래 키 0개 | 높음(결정론적 카운트) |
| V11 뒤 8개 테이블의 변경은 슈퍼유저도 `P0001`로 거절된다 | PostgreSQL 통합 시험 24문장, 변이 M1·M2 | 높음 |
| 가드는 INSERT 비용을 측정 가능한 만큼 늘리지 않는다 | 두 번째 시도 깨끗한 쌍 5~6개, 네 칸 모두 \|p50 차이\| ≤ 7.8%·≤ 0.038 ms | 중간(단일 호스트, DB 단독, 첫 시도는 오염으로 버림) |
| `paypal_webhook_retry_attempt_history`는 추가 전용이 아니라 한 번 종결되는 이력이다 | 운영 UPDATE 4건 모두 종결, V1 트리거의 허용 전이 | 높음 |
| 런타임 역할은 감사·이벤트 12개를 고칠 수 없다 | 역할 시험 `42501`, 변이 M3 | 높음(역할 분리를 켠 배포에 한함) |

## 삭제한 파일의 내용

측정·변이 구동 스크립트(`bench.py`, `analyze.py`, `mut.sh`, 사용처 집계 `count.py`)와 원자료(`results.tsv`)는 커밋하지 않았다. 방법과 모든 매개변수는 위 "측정 방법"과 이 절에 있다.

- 측정 문장(이벤트 로그): `WITH k AS (SELECT gen_random_uuid() AS w) INSERT INTO order_inventory_event (workflow_id, order_id, aggregate_version, event_type, last_error_code) SELECT k.w, k.w, COALESCE(MAX(e.aggregate_version), 0) + 1, 'RESERVATION_RESERVED', NULL FROM k LEFT JOIN order_inventory_event e ON e.workflow_id = k.w GROUP BY k.w;`
- 측정 문장(재고 조정): `CapacityAdjustmentRepository.record`의 CTE에서 delta = 1, `seller_id`·`location_id` 고정 UUID, `sku_id = 'sku-' || :client_id`, 멱등 키 `gen_random_uuid()::text`, 사유 `'bench'`. 재고 풀은 `sku-0`~`sku-15` 16행(`session_replication_role = replica`로 준비).
- 암 전환: 무 = `DROP TRIGGER IF EXISTS <표>_append_only ON <표>; DROP TRIGGER IF EXISTS <표>_no_truncate ON <표>;`, 유 = 같은 두 줄 뒤 V11과 같은 `CREATE TRIGGER` 두 문장. 전환마다 `pg_trigger`의 트리거 수(0 또는 2)를 확인했다.
- run: `pgbench -n -M prepared -c <c> -j <c> -T 3`(데우기, 버림) → `CHECKPOINT` → `pg_current_wal_lsn()` → `pgbench -n -M prepared -c <c> -j <c> -T 10 --log --log-prefix=<id>` → `pg_wal_lsn_diff`. p50·p99는 로그 3열(마이크로초)을 정렬한 중앙값과 `floor(0.99 n)`번째 값.
- 오염 판정 입력: run 직전 호스트 `sysctl -n vm.loadavg` 첫 값, `docker ps --filter label=atlasexp` 중 태그가 `audit720`이 아닌 컨테이너의 `docker stats --no-stream` CPU 최댓값, pgbench의 `number of failed transactions`.
