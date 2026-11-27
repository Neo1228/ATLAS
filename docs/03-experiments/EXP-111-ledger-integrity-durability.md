# EXP-111: 원장 무결성·역할 분리·백업 복원을 실제 스키마와 실제 서비스 흐름 데이터로 측정하고 출시할 가드를 정한다

- 상태: ✓ 완료(PostgreSQL 18.6, 단일 호스트, 비용은 칸마다 깨끗한 run n = 5~8, 복원 훈련은 각 n = 1, 시험과 변이 확인 포함). 가설과 반증 조건은 첫 측정 전 커밋(`504cae77`)으로 고정했고 사전 등록 정정 1건(외래 키)은 첫 비용 측정 전에 기록했다. 실제 PayPal 대조와 호스트 유실 보호는 하지 못했고 `한계`에 이유를 적었다.
- 날짜: 2026-11-27
- 관련: [#714](https://github.com/Neo1228/ATLAS/issues/714), [EXP-022](EXP-022-settlement-app-write-cost.md), [EXP-045](EXP-045-settlement-balance-index.md), [EXP-109](EXP-109-resilience-reapproach.md) 후속 R1, [ADR-0052](../02-decisions/adr/ADR-0052-settlement-fee-reserve-payout.md), [ADR-0100](../02-decisions/adr/ADR-0100-settlement-ledger-guards.md), D-179

## 문제 한 문장

`settlement_journal`·`settlement_entry`는 DB가 어떤 UPDATE·DELETE·TRUNCATE도, 차변과 대변이 다른 분개도, 분개 없는 항목도 거절하지 않고(현재 트리거 14개 중 원장 대상 0개, `settlement_entry`에 외래 키 없음), 앱과 마이그레이션이 같은 계정이라 앱이 침해되면 원장을 고칠 수 있으며, 이를 찾는 점검 질의와 복원 절차가 없다. 그래서 결함이나 수동 SQL이 금액을 조용히 틀리게 해도 사람이 우연히 보기 전에는 알 수 없다.

## 코드 읽기 결과 (측정 전, 기준 `origin/main` 8b621e3e)

- 원장 쓰기 경로는 `SettlementJournalRepository`와 `SettlementPayoutServiceImpl.recordPayoutJournal`뿐이고 모두 한 `@Transactional` 안에서 분개 행을 먼저, 항목 행을 뒤에 넣는다. 운영 코드의 원장 `UPDATE`/`DELETE`는 0건.
- 균형의 뜻은 고전 복식부기와 다르다. 항목은 6개 계정(`PSP_CLEARING`, `SELLER_PAYABLE`, `SELLER_RESERVE`, `PLATFORM_FEE`, `PLATFORM_PAYOUT_FEE`, `PLATFORM_PAYOUT_FX_ROUNDING`)에 방향(DEBIT·CREDIT)과 양수 금액을 쓴다. `PLATFORM_FEE`는 CREDIT만, 지급 수수료·환율 반올림은 DEBIT만 허용한다(CHECK `settlement_entry_account_side`). capture는 `PSP_CLEARING` 차변 = 판매자 지급분 + 수수료 + 보류, 환불은 `PSP_CLEARING` 대변 = `SELLER_RESERVE` 차변 + `SELLER_PAYABLE` 차변, 지급은 `PSP_CLEARING` 대변 = 지급액 + 환율 반올림 + 제공자 수수료. 즉 분개마다 차변 합 = 대변 합이어야 하는 닫힌 구조다. 이 불변식을 강제하는 제약·트리거는 현재 없다.
- 분개 종류는 `settlement_journal_owner_xor` CHECK가 네 가지(capture, checkout, refund, payout)로 가른다. 정정용 종류는 없다. `PLATFORM_FEE`에는 DEBIT이 허용되지 않아 수수료 항목을 상쇄하는 역분개 행은 현재 CHECK로는 만들 수 없다(가설 H9).
- 원장 `UPDATE`/`DELETE`/`TRUNCATE`를 쓰는 시험은 세 클래스의 `@BeforeEach` 각 3문장(`SettlementDbFailureAtomicityIntegrationTest`, `SettlementFeeReservePayoutIntegrationTest`, `SettlementPayoutFailureRetryIntegrationTest`)이고, 분개·항목을 원시 SQL로 넣는 시험은 `SettlementDbFailureAtomicityIntegrationTest`와 `SellerStatementIndexIntegrationTest`다(항목 없는 분개를 만든다). 이 시험들은 지연 균형 가드 후보가 깨뜨릴 수 있다.
- 마이그레이션에 `GRANT`·`REVOKE`·`CREATE ROLE`은 0건. 기본 구성은 앱과 Flyway가 같은 데이터소스 계정(`atlas`)이다. EXP-109 R1은 Flyway가 URL `options`(statement_timeout)를 물려받음을 확인했고 해법으로 `spring.flyway.url`/`user`를 제안했다.

## 측정 방법 (사전 등록)

공통: PostgreSQL 18.6 Testcontainers 또는 임시 컨테이너, 마이그레이션 V1~V6(가드 후보는 시험 암 DDL) 적용, JDK 25. 비용 측정은 호스트 잠금(`measure-lock.sh`)을 쥔 구간에서만 하고 run마다 잠금 상태(부하 load1, 다른 라벨 컨테이너)를 적는다. load1 > 12이거나 다른 라벨 스택이 있으면 오염으로 표시하고 머리 수치에서 뺀다. 정합성(통과·실패) 확인은 부하와 무관하므로 `nice` 한 번에 하나씩 돌린다.

1. 불변식 질의 세트(읽기 전용): I1 분개별 차변 합 = 대변 합, I2 분개·판매자별 균형, I3 항목 없는 분개, I4 분개 없는 항목, I5 capture 분개의 수수료·보류 스냅샷 열 = 해당 계정 항목, I6 지급 분개 = 지급 행(금액, 환율 반올림, 수수료, 상태 CONFIRMED 단 한 건), I7 환불 예약(POSTED) = 환불 분개 1:1, I8 checkout 분개의 `settlement_seller_allocation` 합 = 분개 총액, I9 같은 (분개, 계정, 판매자) 중복 없음(고유 인덱스가 이미 막음, 확인용). 데이터는 실제 서비스를 통해 만든다(capture, checkout capture, 전액 환불, 부분 환불 2회, 보류 소진 환불, 지급 요청·확정·실패 재시도). 음성 대조군은 위반을 하나씩 주입하고 해당 질의만 잡는지 본다.
2. 가드 후보(암): A0 현재(V6), A1 추가 전용 트리거(UPDATE·DELETE 행 트리거 + TRUNCATE 문 트리거, 원장 3개 테이블), A2 외래 키(`settlement_entry`·`settlement_seller_allocation` → `settlement_journal`), A3 지연 균형 제약 트리거(항목 `AFTER INSERT`, 항목마다), A4 지연 균형 제약 트리거(분개 `AFTER INSERT`, 분개마다, 항목 ≥ 1 포함), A5 = A1+A2+A4, A6 = A1+A2+A3+A4. 작업 부하는 pgbench 사용자 스크립트(컨테이너 안): W1 capture 모양(분개 1 + 항목 4, 한 transaction), W3 checkout 3 판매자(분개 1 + 항목 12 + 할당 3). 동시성 c=1(지연)과 c=4(처리량). 암마다 새 데이터베이스(템플릿 복사), 암 순서는 run마다 섞고, run당 12 s, n = 5. 지표는 pgbench의 transaction 수·지연 평균·p50·p95(`-l` 로그), run 전후 `pg_current_wal_lsn` 차이를 transaction 수로 나눈 분개당 WAL 바이트. 측정 전 `CHECKPOINT`.
3. 역할 분리: 런타임 역할 `atlas_runtime`(비소유, LOGIN)과 소유 역할(Flyway)을 나누고 원장 3개 테이블에서 UPDATE·DELETE·TRUNCATE를 회수한다. PostgreSQL 시험으로 거절 SQLSTATE와 소유자의 마이그레이션 성공, 런타임의 트리거 비활성화 거절을 본다. 기존 원장 통합 시험 클래스를 제한 역할로 실행한다(데이터소스 사용자만 바꾼 사본, 삭제 3곳은 소유 계정 연결로 대체).
4. 백업·복원: 실제 흐름 데이터에 규모 증식(분개 20만 건)을 더한 컨테이너에서 `pg_dump -Fc` 크기·덤프 시간·`pg_restore` 시간, `pg_basebackup` + WAL 아카이브 + `recovery_target_time` PITR 복원 시간과 복원 후 불변식 질의. 사고(삭제 문장)를 주입하고 그 직전 시각으로 되돌린다.
5. 기존 데이터에 적용할 때의 안전성: 위반 행을 가진 DB에 트리거·외래 키를 적용해 무엇이 거절되고 무엇이 통과하는지, 사전 점검 질의가 그 위반을 지목하는지 본다.

## 가설과 반증 조건 (측정 실행 전 등록)

판정 규칙: 비용 비교는 n = 5 중앙값 대 A0 중앙값. 정합성 가설은 건수 0과 SQLSTATE로 판정한다.

| # | 가설 | 반증 조건 | 지표 |
|---|---|---|---|
| H1 | 서비스가 만든 정상 흐름 데이터에서 I1~I9가 모두 0건이다 | 한 질의라도 1건 이상 | 질의별 위반 건수 |
| H1n | 주입한 위반은 의도한 질의가 잡고 정상 데이터에서는 0건이다 | 주입 위반 중 하나라도 어떤 질의에도 안 잡힘, 또는 정상 기준선에서 1건 이상 | 주입 종류별 잡은 질의·건수 |
| H2 | 현재(A0)는 앱 계정으로 항목 금액 UPDATE, 항목 DELETE, 불균형 분개 커밋, 분개 없는 항목 INSERT가 모두 오류 없이 성공한다 | 넷 중 하나라도 DB가 거절 | 문장별 성공 여부 |
| H3a | A1은 두 테이블에서 UPDATE·DELETE·TRUNCATE 6문장을 모두 거절하고 정상 흐름 INSERT는 막지 않는다 | 6문장 중 하나라도 통과, 또는 기존 원장 시험(삭제 3곳 제외)에서 새 실패 | 문장별 SQLSTATE, 시험 결과 |
| H3b | A4(와 A3)는 불균형·항목 없는 분개의 커밋을 거절하고 정상 흐름은 거절하지 않는다 | 불균형 커밋이 통과, 또는 정상 흐름 거짓 거절 1건 이상 | 커밋 결과, 흐름 수 |
| H3c | A2는 분개 없는 항목 INSERT를 거절한다 | 통과 | SQLSTATE |
| H4 | 분개 삽입 비용 증가가 암마다 작다: p50 지연 증가 ≤ 25%이고 ≤ 0.3 ms, 분개당 WAL 증가 ≤ 10%. A1은 INSERT에서 발동하지 않으므로 0에 가깝다. 행마다 도는 A3가 분개마다 도는 A4보다 크다 | 암의 n = 5 중앙값이 임계를 넘으면 그 암은 임계 초과로 판정, A3 ≤ A4이면 "A3가 더 크다" 기각 | p50, p95, 처리량, WAL B/분개 |
| H5 | 제한 런타임 역할로도 앱이 끝까지 동작한다: 원장 통합 시험이 삭제 3곳 대체 외에는 0 실패이고, 원장 UPDATE·DELETE·TRUNCATE·트리거 비활성화는 SQLSTATE 42501로 거절된다 | 권한 오류(42501)로 인한 시험 실패 1건 이상, 또는 거절 4종 중 통과 | 시험 수·실패 수, SQLSTATE |
| H6 | `spring.flyway.url`/`user`로 Flyway 연결을 분리하면 앱 URL `statement_timeout=1500`에서도 3 s 마이그레이션이 성공한다 | 취소됨 | 마이그레이션 결과 |
| H7 | `pg_dump` 복원과 PITR 복원 뒤 원장 행 수가 기준 시각의 행 수와 같고 I1~I9가 0건이다 | 행 수 불일치 또는 위반 1건 이상 | 행 수, 질의 결과 |
| H8 | 기존 데이터에 위반이 있어도 `CREATE TRIGGER`는 성공하고(기존 행을 검사하지 않음) 외래 키 `VALIDATE`는 위반 행에서 실패한다. 사전 점검 질의는 둘 다 지목한다 | 트리거가 기존 행 때문에 실패, 또는 사전 점검이 위반을 놓침 | 적용 결과, 질의 건수 |
| H9 | 현재 CHECK로는 `PLATFORM_FEE` DEBIT 역분개 행을 만들 수 없다(정정 경로에는 CHECK 변경이 필요하다) | DEBIT 행이 현재 스키마에서 성공 | SQLSTATE |

## 대안

- A0 유지(탐지·거절 수단 없음). 근거는 이 실험이 H2로 대는 "현재 거절 0".
- 주기 점검만(탐지 전용, 쓰기 비용 0, 거절 없음). 질의 비용을 측정한다.
- 역할 분리만(소유자는 우회 가능), 트리거만(소유자가 비활성화 가능). 둘 다 서로의 빈틈이라 조합을 비교한다.
- 이벤트 소싱·외부 원장 서비스(TigerBeetle 등)는 이번 범위 밖(별도 시스템 도입, 이 저장소의 단일 PostgreSQL 결정과 충돌).

## 한계 (실행 전에 알고 있는 것)

- 단일 호스트, Colima VM, Apple Silicon. 지연 절댓값은 맥락용이고 결론은 상대 증가와 건수로 낸다.
- 비용은 pgbench의 SQL 모양 재현이며 앱 전체 경로(HTTP, 잠금 경합)는 EXP-022 하네스의 몫이다.
- 외부 PayPal 대사는 Sandbox 보고서가 없어 복원 후 대사 절차는 기존 복구 작업 상태 읽기로 쓴 절차 문서이며 실제 PayPal 대조는 하지 않는다(Live gate LG-1 대기, D-170).

## 사전 등록 정정 (2026-11-27, 첫 비용 측정 전)

- 사전 등록의 "`settlement_entry`에 외래 키 없음"은 틀렸다. V1이 `settlement_entry.journal_id`와 `settlement_seller_allocation.journal_id`의 외래 키(`settlement_entry_journal_id_fkey`, `settlement_seller_allocation_journal_id_fkey`)를 이미 갖고 있다(V1~V6을 적용한 데이터베이스에서 `pg_constraint` 조회). 그래서 H3c(A2)는 측정 없이 이미 참이고 A2 암(외래 키 추가)은 만들지 않았다. 측정한 A5는 A1+A4, A6은 A1+A3+A4다(사전 등록의 A5·A6에서 A2를 뺀 것). 이슈 #714 본문도 같은 오류를 고쳤다.
- 사전 등록 뒤에 V7로 실제 구현한 가드는 A6(추가 전용 + 분개 지연 균형 + 항목 지연 균형)이다. 이유는 `해석`에 있다.

## 결과

공통 조건: PostgreSQL 18.6(`postgres:18.6`, Colima VM), V1~V6은 저장소 마이그레이션 파일을 `psql`로 순서대로 적용했고(H2·H8·비용·복원), 시험(IT)은 Flyway가 적용한다. JDK 25.

### 현재 스키마(A0)가 받아들이는 것 (H2, H9)

앱 계정(소유자)으로 V6 스키마에 정상 분개 1건(항목 4개)을 넣은 뒤 실행했다. n = 문장당 1회(결정적).

| 문장 | 결과 |
|---|---|
| `UPDATE settlement_entry SET amount_won = 9000 WHERE account_code = 'SELLER_PAYABLE'` | 성공(1행) |
| `DELETE FROM settlement_entry WHERE account_code = 'PLATFORM_FEE'` | 성공(1행) |
| 분개 1건 + 차변 항목 하나만 넣고 commit(불균형 분개) | 성공(commit됨) |
| 존재하지 않는 `journal_id`로 항목 INSERT | **거절**(V1 외래 키 `settlement_entry_journal_id_fkey`) |
| `TRUNCATE settlement_entry` | 성공(분개 2건, 항목 0건이 남음) |
| `PLATFORM_FEE`에 `DEBIT` 항목 INSERT | 거절(CHECK `settlement_entry_account_side`) |

H2("넷 모두 성공")는 반증 조건("넷 중 하나라도 거절")에 걸려 **참이 아니었다**: 분개 없는 항목은 V1 외래 키가 이미 막는다. 나머지 셋(항목 수정, 항목 삭제, 불균형 commit)과 TRUNCATE는 오류 없이 성공했고, 그 뒤 원장은 조용히 틀어진 채 남는다. H9는 참이다: 수수료 항목을 상쇄하는 `DEBIT` 행은 현재 CHECK로 만들 수 없다.

### 불변식 점검 질의 (H1, H1n)

질의는 `atlas-settlement/src/main/resources/ledger-audit/ledger-audit.sql` 한 파일이 9개 점검을 소유한다(읽기 전용). 균형의 뜻은 고전 복식부기가 아니라 이 모델의 계정·방향 규칙이다: capture는 `PSP_CLEARING` 차변 = `SELLER_PAYABLE` 대변 + `PLATFORM_FEE` 대변 + `SELLER_RESERVE` 대변, 환불은 `PSP_CLEARING` 대변 = `SELLER_RESERVE` 차변 + `SELLER_PAYABLE` 차변, 지급은 `PSP_CLEARING` 대변 = `SELLER_PAYABLE` 차변 + 환율 반올림 차변 + 제공자 수수료 차변. 데이터는 서비스 API로만 만들었다(`SettlementLedgerAuditIntegrationTest`): capture 3건(보류·수수료 10%), checkout 1건(판매자 2명), 보류를 소진하는 환불 1건과 부분 환불 1건, 예약 후 해제한 환불 1건, 환율 반올림 11·제공자 수수료 25의 지급 확정 1건, 확정하지 않은 지급 요청 1건. 결과는 분개 7건, 항목 29건이다.

| 점검 | 정상 흐름 위반 건수 | 주입한 위반(트랜잭션 안에서 `session_replication_role = replica`로 트리거·외래 키를 건너뛰고 넣은 뒤 롤백) | 잡은 점검과 건수 |
|---|---|---|---|
| `JOURNAL_UNBALANCED` | 0 | capture 분개의 `SELLER_PAYABLE` 항목 +1원 | `JOURNAL_UNBALANCED` 1, `SELLER_UNBALANCED` 1 |
| `SELLER_UNBALANCED` | 0 | checkout 분개에서 판매자 A 항목 +100, 판매자 B 항목 -100(분개 합은 그대로) | `SELLER_UNBALANCED` 2(분개 단위 점검은 0) |
| `JOURNAL_WITHOUT_ENTRIES` | 0 | 항목 없는 분개 | `JOURNAL_WITHOUT_ENTRIES` 1, `CAPTURE_SPLIT_MISMATCH` 1 |
| `ENTRY_WITHOUT_JOURNAL` | 0 | 분개 없는 항목(외래 키 우회) | `ENTRY_WITHOUT_JOURNAL` 1, `JOURNAL_UNBALANCED` 1, `SELLER_UNBALANCED` 1 |
| `CAPTURE_SPLIT_MISMATCH` | 0 | capture 분개 `fee_amount_won` +1 | `CAPTURE_SPLIT_MISMATCH` 1 |
| `REFUND_CLEARING_MISMATCH` | 0 | 환불 분개 `gross_amount_won` +1 | `REFUND_CLEARING_MISMATCH` 1, `REFUND_RESERVATION_MISMATCH` 1 |
| `REFUND_RESERVATION_MISMATCH` | 0 | 예약 상태 `POSTED`를 `RELEASED`로 | `REFUND_RESERVATION_MISMATCH` 1 |
| `CHECKOUT_ALLOCATION_MISMATCH` | 0 | 판매자 할당 +1 | `CHECKOUT_ALLOCATION_MISMATCH` 1 |
| `PAYOUT_JOURNAL_MISMATCH` | 0 | 확정 지급의 `provider_fee_won` +1 | `PAYOUT_JOURNAL_MISMATCH` 1 |

H1 참(9개 점검 모두 0건, 위반 후보 없음: 실제 흐름에서 버그는 찾지 못했다). H1n 참(주입 9종 모두 의도한 점검이 잡았고 롤백 뒤 기준선 0). 한 점검이 둘 이상 올리는 것은 위반이 겹치기 때문이며, `SELLER_UNBALANCED`만이 잡는 위반(판매자 사이 상쇄)이 있어 분개 단위 점검만으로는 부족하다. 점검에 넣지 않은 것: 판매자 지급 가능 잔액이 음수가 아니라는 불변식. 지급 뒤 환불이 오면 음수가 되는 것이 설계이고 지표(`settlement.payout.negativepayablesellercount`)와 시험(`successfulRefundAfterDispatchedPayoutSurfacesNegativePayableCreditExposure`)이 이미 있다.

### 위반이 있는 기존 데이터에 V7을 적용하면 (H8)

V6 스키마에 합성 원장(분개 2,221건, 항목 8,480건)을 넣고 위반 2건을 주입했다(항목 금액 +1원인 분개 1건, 항목 없는 분개 1건). 사전 점검은 `JOURNAL_UNBALANCED` 1, `SELLER_UNBALANCED` 1, `JOURNAL_WITHOUT_ENTRIES` 1, `CAPTURE_SPLIT_MISMATCH` 1을 지목했다. 이 상태에서 V7은 성공했다(기존 행을 검사하지 않음, 0.5~1.3 s). 적용 뒤에도 같은 위반이 남고, 그 분개의 항목 수정은 `settlement_ledger_is_append-only`로 거절됐다. 새 불균형 분개는 commit에서 `settlement_journal_unbalanced`로 거절됐다. 소유 계정이 한 트랜잭션에서 트리거를 끄고 고친 뒤 켜는 정정은 성공했고(`UPDATE 1`), 정정 뒤 점검은 항목 없는 분개의 두 건만 남겼다. 분개·항목 테이블의 트리거 6개는 모두 켜짐(`tgenabled = O`)이다. H8 참(트리거 생성은 기존 위반 때문에 실패하지 않았고 사전 점검이 위반을 지목). 외래 키 `VALIDATE` 부분은 외래 키가 이미 있어 해당 없음. `ALTER TABLE ... DISABLE TRIGGER`와 `CREATE TRIGGER`는 둘 다 `SHARE ROW EXCLUSIVE` 잠금을 잡는다(`pg_locks`로 확인): 조회는 막지 않고 쓰기는 commit까지 기다린다.

### 가드 비용 (H4)

조건: 컨테이너 하나(`postgres:18.6`), `pgbench -n -f <워크로드> -c C -j C -T T -l`, 암마다 새 데이터베이스(템플릿 복사), 암 순서는 run마다 섞음, 호스트 잠금 아래. 행 210개 중 깨끗한 행(전후 `measure-lock.sh status`의 load1 ≤ 12, 다른 라벨 컨테이너 없음) 112개만 표에 넣었다(깨끗한 행의 load1 2.3~8.9). 첫 실행(T = 12 s, 예열 3 s)은 90행 중 10행만 깨끗했고 나머지는 다른 에이전트의 스택과 load1 최대 90까지 겹쳐 뺐다. 잠금 구간에서 다시 채운 행은 T = 6 s, 예열 2 s다. 변화는 같은 칸의 A0 중앙값 대비다. 처리량 [최소, 최대]는 깨끗한 run들의 범위다.

#### W1 capture 모양(분개 1·항목 4), c = 1

| 암 | n(깨끗/전체) | 처리량 tx/s 중앙값 [최소, 최대] | 변화 | p50 ms | 변화 | p95 ms | 변화 | WAL B/분개(LSN 차) | 변화 |
|---|---|---|---|---|---|---|---|---|---|
| A0 현재(V6) | 6/13 | 2193 [1895, 2490] | +0.0% | 0.410 | +0.0% (+0.000 ms) | 0.673 | +0.0% | 2359 | +0.0% |
| A1 추가 전용 | 6/13 | 2269 [2072, 2351] | +3.4% | 0.410 | +0.1% (+0.001 ms) | 0.601 | -10.8% | 3077 | +30.4% |
| A3 균형(항목) | 6/13 | 2139 [1534, 2197] | -2.4% | 0.439 | +7.1% (+0.029 ms) | 0.611 | -9.3% | 2670 | +13.2% |
| A4 균형(분개) | 6/13 | 2082 [1556, 2248] | -5.1% | 0.419 | +2.3% (+0.010 ms) | 0.792 | +17.5% | 2389 | +1.3% |
| A5 = A1 + A4 | 6/13 | 2192 [1984, 2476] | -0.0% | 0.417 | +1.8% (+0.008 ms) | 0.627 | -6.8% | 2343 | -0.7% |
| A6 = A1 + A3 + A4(V7) | 6/13 | 2035 [1624, 2279] | -7.2% | 0.452 | +10.1% (+0.042 ms) | 0.690 | +2.4% | 2341 | -0.8% |

#### W12 checkout 3판매자(분개 1·항목 12), c = 1

| 암 | n(깨끗/전체) | 처리량 tx/s 중앙값 [최소, 최대] | 변화 | p50 ms | 변화 | p95 ms | 변화 | WAL B/분개(LSN 차) | 변화 |
|---|---|---|---|---|---|---|---|---|---|
| A0 현재(V6) | 8/12 | 837 [702, 1144] | +0.0% | 0.912 | +0.0% (+0.000 ms) | 2.204 | +0.0% | 5839 | +0.0% |
| A1 추가 전용 | 8/12 | 1091 [777, 1125] | +30.4% | 0.859 | -5.9% (-0.053 ms) | 1.213 | -45.0% | 5812 | -0.5% |
| A3 균형(항목) | 8/12 | 939 [778, 1016] | +12.2% | 0.962 | +5.5% (+0.050 ms) | 1.680 | -23.8% | 5827 | -0.2% |
| A4 균형(분개) | 7/12 | 1056 [902, 1104] | +26.2% | 0.895 | -1.9% (-0.017 ms) | 1.248 | -43.4% | 5812 | -0.5% |
| A5 = A1 + A4 | 7/12 | 1042 [859, 1090] | +24.5% | 0.887 | -2.7% (-0.025 ms) | 1.350 | -38.7% | 5812 | -0.5% |
| A6 = A1 + A3 + A4(V7) | 8/12 | 946 [775, 998] | +13.0% | 0.972 | +6.6% (+0.060 ms) | 1.488 | -32.5% | 6095 | +4.4% |

#### W1 capture 모양, c = 4

| 암 | n(깨끗/전체) | 처리량 tx/s 중앙값 [최소, 최대] | 변화 | p50 ms | 변화 | p95 ms | 변화 | WAL B/분개(LSN 차) | 변화 |
|---|---|---|---|---|---|---|---|---|---|
| A0 현재(V6) | 5/10 | 5412 [4675, 6393] | +0.0% | 0.668 | +0.0% (+0.000 ms) | 1.173 | +0.0% | 2363 | +0.0% |
| A1 추가 전용 | 5/10 | 6164 [4572, 6312] | +13.9% | 0.594 | -11.1% (-0.074 ms) | 0.986 | -15.9% | 2364 | +0.0% |
| A3 균형(항목) | 5/10 | 5309 [4893, 5916] | -1.9% | 0.670 | +0.3% (+0.002 ms) | 1.210 | +3.2% | 2702 | +14.3% |
| A4 균형(분개) | 5/10 | 5716 [4862, 6102] | +5.6% | 0.632 | -5.4% (-0.036 ms) | 1.053 | -10.2% | 2363 | -0.0% |
| A5 = A1 + A4 | 5/10 | 5412 [4334, 5744] | -0.0% | 0.651 | -2.5% (-0.017 ms) | 1.213 | +3.4% | 2363 | +0.0% |
| A6 = A1 + A3 + A4(V7) | 5/10 | 5560 [4150, 5963] | +2.7% | 0.650 | -2.7% (-0.018 ms) | 1.107 | -5.6% | 2360 | -0.1% |

#### 판정(사전 등록 임계: p50 증가 ≤ 25%이고 ≤ 0.3 ms)

| 암 | 판정 |
|---|---|
| A1 추가 전용 | 임계 이내(세 칸 모두) |
| A3 균형(항목) | 임계 이내(세 칸 모두) |
| A4 균형(분개) | 임계 이내(세 칸 모두) |
| A5 = A1 + A4 | 임계 이내(세 칸 모두) |
| A6 = A1 + A3 + A4(V7) | 임계 이내(세 칸 모두) |

행마다 도는 A3가 분개마다 도는 A4보다 크다는 가설: p50 중앙값 W1c1: A3 0.439 ms 대 A4 0.419 ms, W12c1: A3 0.962 ms 대 A4 0.895 ms, W1c4: A3 0.670 ms 대 A4 0.632 ms. 세 칸 모두 A3 > A4(가설 참).

#### 원인 추적: 트리거가 얼마나 도는가

A6(V7)에 `track_functions = 'all'`을 켜고 20,000 transaction을 돌렸다(`pg_stat_user_functions`, n = 1). W1: 함수 100,000회 호출, 호출당 0.0064 ms(합 638 ms) → 분개당 5회 × 0.0064 = 0.032 ms, 측정한 A6 p50 증가는 +0.042 ms(+10.1%). W12: 함수 260,000회 호출, 호출당 0.0068 ms(합 1776 ms) → 분개당 13회 × 0.0068 = 0.088 ms, 측정한 A6 p50 증가는 +0.060 ms(+6.6%). 추가 전용 트리거 함수 `reject_settlement_ledger_mutation()`은 INSERT 전용 부하에서 한 번도 불리지 않는다(통계에 없음). 균형 점검 SELECT 하나를 단발로 `EXPLAIN (ANALYZE, BUFFERS)`한 실행 시간은 0.129 ms(첫 실행이라 호출당 값보다 크다)이며 `settlement_entry_account_seller_once`(분개 ID가 선두 열)의 비트맵 인덱스 스캔(`Buffers: shared hit=4`, 계획 시간은 첫 호출 한 번뿐이고 PL/pgSQL이 계획을 재사용)으로 풀린다. 즉 비용은 "분개 하나당 함수 호출 1(분개 트리거) + 항목 수(항목 트리거)번, 호출마다 인덱스 한 번 읽기"이고 쓰기(WAL)는 없다.

#### 분개당 WAL: W1, `pg_walinspect`로 FPI를 뺀 값(10,000 transaction)

| 암 | n | 레코드/분개 중앙값 | 비FPI B/분개 중앙값 |
|---|---|---|---|
| A0 | 3 | 23.17 | 2308 |
| A1 | 3 | 23.17 | 2306 |
| A3 | 3 | 23.20 | 2309 |
| A4 | 3 | 23.20 | 2309 |
| A5 | 3 | 23.20 | 2309 |
| A6 | 3 | 23.17 | 2308 |

#### 분개당 WAL: W12, `pg_walinspect`로 FPI를 뺀 값(5,000 transaction)

| 암 | n | 레코드/분개 중앙값 | 비FPI B/분개 중앙값 |
|---|---|---|---|
| A0 | 3 | 55.58 | 5746 |
| A1 | 3 | 55.51 | 5740 |
| A3 | 3 | 55.51 | 5740 |
| A4 | 3 | 55.51 | 5740 |
| A5 | 3 | 55.57 | 5745 |
| A6 | 3 | 55.51 | 5740 |

표 위쪽 LSN 차 기반 WAL 열은 `CHECKPOINT` 직후라 전체 페이지 이미지(FPI)가 섞여 run마다 크게 흔들린다(A1이 +30%로 보이는 것은 이 잡음이다). `pg_walinspect`로 FPI를 빼면 WAL은 암과 무관하게 같다: W1 2306~2309 B/분개(레코드 23.2), W12 5740~5746 B/분개(레코드 55.5). 균형 점검은 읽기뿐이고 추가 전용 트리거는 INSERT에서 불리지 않으므로 WAL 비용은 0이다(H4의 WAL 조건 참).

### V7 가드가 막는 것과 막지 않는 것 (H3a, H3b, H3c)

`SettlementLedgerGuardIntegrationTest`(Flyway V1~V7, Testcontainers PostgreSQL 18.6, 시험 8개, 모두 통과):

| 시도 | 결과 |
|---|---|
| `settlement_entry`·`settlement_journal`의 `UPDATE`·`DELETE`, `TRUNCATE settlement_entry`, `TRUNCATE settlement_seller_allocation`, `TRUNCATE settlement_journal CASCADE`, `settlement_seller_allocation`의 `UPDATE`·`DELETE` | 모두 `settlement_ledger_is_append-only`로 거절, 행 수 그대로 |
| 일반 `TRUNCATE settlement_journal` | PostgreSQL이 외래 키 때문에 트리거 전에 거절(`cannot truncate a table referenced in a foreign key constraint`, 두 번째 방어선) |
| 분개 + 항목 둘(차변 500, 대변 499)을 한 transaction에서 commit | commit에서 `settlement_journal_unbalanced`(SQLSTATE 23000)로 거절, 분개 0건 남음 |
| 항목 없는 분개 commit | 같은 오류로 거절 |
| commit된 균형 분개에 대변 항목 1원을 나중 transaction에서 추가 | 항목 지연 균형 트리거가 거절(분개 트리거만으로는 못 막는 경우) |
| 정상 capture, 같은 capture 재생(`ON CONFLICT DO NOTHING`), 환불 예약·게시 | 거절 없이 commit, 점검 0건 |

H3a 참(6종 문장 모두 거절, 정상 흐름 거짓 거절 0). H3b 참(불균형·항목 없는 분개 거절, 정상 흐름 거짓 거절 0). H3c는 사전 등록 정정대로 V1 외래 키가 이미 참이었다.

기존 시험 영향(사전 등록 반증 조건의 "삭제 3곳 제외 새 실패"): V7을 적용한 채 `com.atlas.settlement.*`, `com.atlas.schema.*`, 새 시험 4개를 돌렸을 때 22개 클래스 128개 시험이 0 실패였다(Gradle 3분 38초). 그 전에 변경이 필요한 곳을 이렇게 찾았다.

- 원장을 비우는 `DELETE FROM settlement_entry/settlement_seller_allocation/settlement_journal` 3문장씩 3개 클래스(`SettlementDbFailureAtomicityIntegrationTest`, `SettlementFeeReservePayoutIntegrationTest`, `SettlementPayoutFailureRetryIntegrationTest`)는 추가 전용 트리거가 막는다. 최소 변경: 세 문장을 시험 지원 클래스 `LedgerTestReset.clearLedger(jdbc)`(EXP-116에서 `AppendOnlyTestReset`으로 이름을 바꿈) 한 호출로 바꾼다. 이 호출은 한 연결에서 `SET session_replication_role = replica`로 트리거를 건너뛰고 자식부터 지운 뒤 `DEFAULT`로 되돌린다(슈퍼유저 연결인 시험 컨테이너에서만 동작, 운영 코드는 쓰지 않음). 운영 규칙을 약하게 하지 않는다: 가드는 그대로이고 시험 연결만 우회한다.
- 예상하지 못한 3개 시험(`SettlementDbFailureAtomicityIntegrationTest`의 `killingBackendDuringPayoutConfirmLeavesStateAndLedgerConsistent`, `replayingAfterConnectionLossProducesExactlyOneJournal`, `repeatedPayoutConfirmationKeepsOneJournalPerPayout`)가 V7에서 실패했다(`DataIntegrityViolationException`, 줄 142·246). 원시 SQL로 분개를 먼저 autocommit하고 항목을 나중 문장에서 넣기 때문에 지연 균형 트리거가 첫 commit에서 거절한 것이다. 서비스 흐름은 항상 한 transaction이라 같은 일이 없다. 최소 변경: `postCapture` 도우미가 분개와 항목을 한 `TransactionTemplate` 안에서 넣고, 재생 시험의 재시도 INSERT가 분개와 두 항목을 한 transaction에서 `ON CONFLICT`로 넣는다. 시험이 확인하려는 것(재생이 분개 1건만 남김, 확정 후 중복 분개 없음)은 그대로다. 변경 후 4개 시험 모두 통과(`SettlementDbFailureAtomicityIntegrationTest` 4/4).
- 그 외 원시 SQL로 분개를 넣는 `SellerStatementIndexIntegrationTest`와 `PayoutStoreIntegrationTest`는 V1만 적용한 스키마를 쓰므로 영향이 없다.

### 런타임 역할 분리 (H5, H6)

`SettlementLedgerRoleSeparationIntegrationTest`(시험 3개, 모두 통과): `scripts/db/runtime-role.sql`을 마이그레이션 전과 첫 기동 뒤 두 번 실행하고, 데이터소스를 `atlas_runtime`으로, `spring.flyway.url/user/password`를 소유 계정으로 지정했다.

| 확인 | 결과 |
|---|---|
| `current_user`와 capture → 환불 예약·게시 → 지급 요청·승인·전송·확정(환율 반올림 11, 수수료 25)을 서비스 API로 실행 | `atlas_runtime`으로 끝까지 성공, 분개 3건, 지급 `CONFIRMED` |
| 런타임 역할의 원장 `UPDATE`·`DELETE` 6문장, `TRUNCATE`, `ALTER TABLE ... DISABLE TRIGGER`, `DROP TRIGGER`, `SET session_replication_role = replica`, `UPDATE flyway_schema_history`, `CREATE TABLE` | 12문장 모두 SQLSTATE `42501`, 항목 수 그대로 |
| 이전에 잘못 부여된 `UPDATE`·`DELETE`·`TRUNCATE`를 스크립트 재실행이 회수 | 재실행 뒤 거절(멱등 수렴) |
| 데이터소스 URL에 `statement_timeout=1500`을 주고 Flyway에 3 s `pg_sleep` 마이그레이션(V99) | 성공(`flyway_schema_history`에 7과 99), 런타임 연결은 `SHOW statement_timeout` = 1500ms, 모든 공개 테이블의 소유자는 `atlas` |

H6 참(Flyway 전용 연결은 앱 URL 옵션을 물려받지 않는다). EXP-109 R1이 "물려받는다"(1.5 s 한도로 3 s 마이그레이션이 취소됨, n=1)를 보였고 이 시험이 그 해법(`spring.flyway.url/user`)을 확인한다(n=1, 결정적).

기존 원장 통합 시험을 제한 역할로 돌린 결과(시험 클래스 사본에서 데이터소스·Flyway 속성만 바꿈, 구동 방법은 `삭제한 파일의 내용`): 13개 클래스 157개 시험이 실패 0개로 통과했다. 시험 뒤 점검 157개 시점 중 위반이 있는 시점은 2개다. 첫 실행(시험 안 DDL까지 런타임 연결로 실행)에서는 시험 안의 DDL(`CREATE FUNCTION/TRIGGER`, `ALTER TABLE ... DISABLE TRIGGER`)이 소유자만 할 수 있는 문장이라 권한 거절(`BadSqlGrammarException`)로 5개 시험이 실패했다(두 번의 첫 실행에서 확인, 한 번은 중간에 종료돼 모든 클래스를 돌지 못함): PayoutOperatorMakerCheckerIntegrationTest.withTheRuleDisabledTheSameOperatorFlowSucceeds; SettlementLedgerIntegrationTest.failedSecondEntryRollsBackOrderAndLedgerThenReconciliationRetries; BuyerFlowIntegrationTest.fulfillmentInsertFailureRollsBackCapturedOrderAndInventoryThenReconciles; OrderRefundLifecycleIntegrationTest.settlementFailureRollsBackRefundStateInventoryAndReversalBeforeRetry; OrderRefundLifecycleIntegrationTest.settlementFailureRollsBackRefundFinalizationAndRetainsPreparedInspectionForRetry. 이 DDL 문장 18줄(4개 클래스)만 소유 계정 연결로 바꾼 사본에서는 13개 클래스 모두 통과한다. 즉 앱의 DML 경로는 런타임 역할로 끝까지 동작하고, 실패는 시험이 DDL로 장애를 주입하는 방식뿐이었다(런타임 역할이 트리거를 만들지 못하는 것은 의도한 거절이다).

### 변이 확인 (가드·스크립트·점검 질의가 시험으로 지켜지는가)

변이마다 파일 하나를 바꾸고 대상 시험 클래스를 돌려 실패한 시험을 기록한 뒤 원본을 복원했다(복원 뒤 `git status`로 해당 경로에 변경 없음을 확인). 한 클래스의 시험은 같은 컨테이너를 쓰므로 변이가 만든 위반(예: 지워진 항목)을 뒤에 도는 전역 점검 시험이 보고 함께 실패한다. 표의 **의도한 시험**이 변이를 지목하는 시험이고 나머지는 이 연쇄 실패다.

| 변이 | 대상 시험 | 의도한 시험(실패함) | 연쇄 실패 |
|---|---|---|---|
| M1 `settlement_entry_append_only` 트리거 삭제 | Guard | `ledgerRowsCannotBeUpdatedDeletedOrTruncated` | 롤백 절차, 정정 절차, 정상 흐름 점검 |
| M2 `settlement_entry_no_truncate` 삭제 | Guard | `ledgerRowsCannotBeUpdatedDeletedOrTruncated` | 정정 절차, 정상 흐름 점검 |
| M3 분개 지연 균형 트리거 삭제 | Guard | `journalWithoutEntriesIsRejectedAtCommit` | 정정 절차, 정상 흐름 점검 |
| M4 항목 지연 균형 트리거 삭제 | Guard | `entryAddedLaterThatUnbalancesACommittedJournalIsRejected` | 정정 절차, 정상 흐름 점검 |
| M5 함수에서 `debit_total <> credit_total` 검사 삭제 | Guard | `unbalancedJournalIsRejectedAtCommitAndLeavesNothing`, `entryAddedLater...` | 정정 절차, 정상 흐름 점검 |
| M6 `settlement_seller_allocation_append_only` 삭제 | Guard | `sellerAllocationRowsAreAppendOnlyToo` | 정상 흐름 점검 |
| M7 `runtime-role.sql`의 원장 `UPDATE`·`DELETE` 회수 삭제 | RoleSeparation | `runtimeRoleCannotMutateTheLedgerOrDisableItsGuards` | 없음 |
| M8 `runtime-role.sql`의 `TRUNCATE` 회수 삭제 | RoleSeparation | 첫 실행에서 **살아남음**(0 실패): 스크립트가 `TRUNCATE`를 처음부터 주지 않아 회수가 중복이었다. 시험에 "잘못 부여된 `UPDATE`·`DELETE`·`TRUNCATE`를 스크립트 재실행이 회수한다"를 넣어 죽이도록 고쳤다(아래 재실행 행) | |
| M9 롤백 스크립트에서 `settlement_entry_append_only` DROP 삭제 | Guard | `documentedRollbackScriptRestoresThePreV7Behavior` | 없음 |
| M10 점검 질의에서 수수료 스냅샷 비교 삭제 | Audit | `injectedViolationsAreCaughtByTheIntendedCheck` | 없음 |

변이 M8 재실행(시험 보강 뒤): 실패 1개로 죽음. 10개 중 첫 실행에서 죽은 변이는 9개다.

### 백업·복원 훈련 (H7)

합성 원장은 분개 222,000건 규모(생성기 N = 200,000, `삭제한 파일의 내용`)에 V7을 적용한 상태다. 훈련마다 n = 1이고(결정적 정합성) 시간은 호스트 잠금 아래 값이지만 한 번이라 맥락용이다. 행 수는 분개·항목·지급·환불 예약 순서다.

| 항목 | 값 |
|---|---|
| 행 수 | 222000/848000/2000/20000 |
| 데이터베이스 크기 | 294 MB |
| 생성 시간, 채운 원장에 V7 적용 시간 | 11.15 s, 0.11 s(행을 읽지 않는다. H8의 2천 분개 데이터베이스에서는 psql 시작 포함 0.5~1.3 s) |
| 불변식 점검 9개 전체 시간(`psql -f`, n = 5) | 중앙값 2.1 s [1.9, 2.4] |
| `pg_dump -Fc` 시간, 크기 | 3.45 s, 65 MB |
| `pg_restore -j 1` / `-j 4` 시간 | 3.85 s / 2.41 s |
| 복원 뒤 행 수(j1, j4), 항목 해시 | 222000/848000/2000/20000, 222000/848000/2000/20000; 해시는 원본과 같다(1b3d855d) |
| 복원 뒤 트리거 수, 점검 | 22개(원본과 같음), 점검 9개 모두 0 |

점검 질의별 시간(한 번): JOURNAL_UNBALANCED 306 ms, SELLER_UNBALANCED 223 ms, JOURNAL_WITHOUT_ENTRIES 84 ms, ENTRY_WITHOUT_JOURNAL 68 ms, CAPTURE_SPLIT_MISMATCH 1,197 ms, REFUND_CLEARING_MISMATCH 100 ms, REFUND_RESERVATION_MISMATCH 27 ms, CHECKOUT_ALLOCATION_MISMATCH 56 ms, PAYOUT_JOURNAL_MISMATCH 20 ms. 가장 오래 걸리는 것은 capture 스냅샷 대조(분개마다 항목 합을 구하는 LATERAL)다.

#### WAL 아카이브 + 시점 복구(PITR)

| 항목 | 값 |
|---|---|
| 기본 백업 시간, 크기(`pg_basebackup -X stream`) | 2.77 s, 333 MB(데이터베이스 294 MB) |
| 기준 이후 쓰기 | 26263 건(W1, c = 2, 10 s) |
| 기준 시각의 행 수(분개/항목)와 항목 해시 | 248263/953052, e52735b9 |
| 사고(`session_replication_role = replica`로 항목 삭제) 뒤 행 수와 점검 | 248263/922789; JOURNAL_UNBALANCED 30,263; SELLER_UNBALANCED 30,263; CAPTURE_SPLIT_MISMATCH 30,263 |
| 아카이브 | WAL 파일 50개(784 MB), `pg_stat_archiver` 성공/실패 50/0 |
| 복원(컨테이너 시작 → 승격) 시간 | 4.21 s |
| 복원 뒤 행 수, 항목 해시 | 248263/953052; 기준과 같다 |
| 복원 뒤 점검 | 점검 9개 모두 0 |

복원 로그(발췌): recovery stopping before commit of transaction 27463, time 2026-11-27 01:48:24.172088+00; redo done at 0/309FB9C8 system usage: CPU: user: 0.28 s, system: 0.42 s, elapsed: 1.42 s; archive recovery complete

#### 방식별로 정직하게 주장할 수 있는 목표(로컬 단일 호스트, 이 훈련 기준)

| 방식 | RPO | RTO(복원 자체) | 근거와 한계 |
|---|---|---|---|
| 현재(백업 없음) | 볼륨 유실 시 전부 | 재구축(데이터 없음) | 코드·문서에 백업·`archive_command` 0건 |
| 야간 `pg_dump` | 덤프 주기(예: 24 h) + 덤프 시간 | 복원 시간 위 표 + 점검 시간 + 앱 기동 | 복원 정합성 n = 1. 복원 뒤 PayPal 대조는 사람 시간이고 측정하지 않음 |
| WAL 아카이브 + PITR | `archive_timeout`(훈련 60 s) + 복사 지연. 아카이브가 DB 볼륨과 다른 저장소일 때만 호스트 유실을 보호 | 위 표의 복원 시간(기준 이후 WAL 길이에 비례하는 재생 포함) | 훈련의 아카이브는 같은 VM 볼륨이라 논리 오류(사고 직전 복원)만 증명. n = 1 |
| 동기 복제 | 0(이론) | 승격 수 초(이론) | 측정하지 않았고 주장하지 않음 |

### 정정 경로: break-glass와 역분개 시제품

추가 전용 아래에서 잘못된 항목을 고치는 길이 둘 있다. 앞의 것만 출시했다.

1. **break-glass(출시)**: 소유 계정이 한 트랜잭션에서 `ALTER TABLE ... DISABLE TRIGGER`, 수정, `ENABLE TRIGGER`. `SettlementLedgerGuardIntegrationTest.ownerCorrectsAnEntryInOneTransactionAndTheGuardIsBackAfterCommit`이 항목 두 건과 분개 스냅샷을 함께 고친 뒤 트리거 두 개가 다시 켜짐(`tgenabled = O`), 같은 항목의 `UPDATE`가 다시 거절됨, 점검 0건을 확인한다. H8의 SQL 훈련도 같은 절차로 위반 분개를 고쳤다. 한계: 정정 이력을 DB가 남기지 않고, `DISABLE TRIGGER`가 `SHARE ROW EXCLUSIVE`를 잡아 commit까지 쓰기가 기다린다.
2. **역분개 분개(출시하지 않음)**: V7 스키마에서 시제품을 돌렸다(원 분개의 항목 방향을 뒤집은 분개를 같은 transaction에 넣음). 결과 4가지. (a) 현재 스키마는 정정 분개를 거절한다(`settlement_journal_owner_xor`가 분개 종류를 capture·checkout·환불·지급 넷으로 고정). (b) `reverses_journal_id` 열과 CHECK 두 개(`owner_xor`, `settlement_entry_account_side`의 `PLATFORM_FEE` 방향)를 바꾸면 정정 분개가 들어가고 균형 트리거가 통과시킨다. (c) 원 분개와 정정 분개를 합친 계정별 순잔액은 `PLATFORM_FEE`·`PSP_CLEARING`·`SELLER_PAYABLE`·`SELLER_RESERVE` 모두 0이고 점검 9개도 0건이다. (d) 그러나 판매자 명세 합계의 capture 분기(`source_refund_id`·`checkout_id`·`source_payout_id`가 모두 NULL인 분개)는 정정 분개를 capture로 세어 `captured`가 10,000이 아니라 20,000으로 나온다. 즉 스키마 변경 + 명세 질의 2곳(`sellerStatementRows`, `sellerStatementTotals`) + 점검 질의 + 운영자 API가 함께 바뀌어야 하는 기능이고 이번 범위(DB 가드)를 넘는다. 재검토 조건은 결정 절에 있다.

### 사전 등록 규칙 평가

| # | 판정 | 근거 |
|---|---|---|
| H1 | 참 | 서비스 흐름 데이터(분개 7, 항목 29)에서 점검 9개 모두 0. 제한 역할로 돌린 기존 시험 157개의 시험 뒤 점검 157개 시점 중 155개가 0이고 2개 시점은 시험 도우미 `postCapture`가 분개의 수수료 스냅샷 열을 0으로 둔 합성 데이터의 `CAPTURE_SPLIT_MISMATCH` 1건(운영 흐름의 위반이 아님) |
| H1n | 참 | 주입 9종 모두 의도한 점검이 잡음, 기준선 0 |
| H2 | 반증 | 5종 중 분개 없는 항목 INSERT는 V1 외래 키가 이미 거절(나머지 4종은 성공) |
| H3a | 참 | 거절 6종, 정상 흐름 거짓 거절 0. 기존 시험은 삭제 3곳 외에 `SettlementDbFailureAtomicityIntegrationTest` 3개가 V7의 지연 균형에 걸려 시험 도우미를 고쳤다(`V7 가드가 막는 것과 막지 않는 것`) |
| H3b | 참 | 불균형·항목 없는 분개 거절, 정상 흐름 거짓 거절 0 |
| H3c | 참(측정 전) | V1 외래 키가 이미 있음 |
| H4 | 참(세 칸, 암 5종 모두 p50 증가 ≤ 25%이고 ≤ 0.3 ms: A6 +10.1%(+0.042 ms), +6.6%(+0.060 ms), -2.7%(-0.018 ms)) | 비용 절 |
| H5 | 참(역할 시험 3개 통과, 12문장 모두 42501, 기존 시험은 시험 안 DDL만 소유 연결이 필요: 13개 클래스 157개 시험이 실패 0개로 통과했다. 시험 뒤 점검 157개 시점 중 위반이 있는 시점은 2개다. 첫 실행(시험 안 DDL까지 런타임 연결로 실행...) | 역할 절 |
| H6 | 참 | Flyway 전용 연결에서 3 s 마이그레이션 성공(앱 URL `statement_timeout` 1.5 s), n = 1 |
| H7 | 참(덤프 복원 항목 해시 일치 True, PITR 복원 행 수·해시 일치 True, 복원 뒤 점검 0건 확인은 복원 절) | 복원 절 |
| H8 | 참 | 위반 데이터에 V7 적용 성공, 사전 점검이 위반 지목 |
| H9 | 참 | `PLATFORM_FEE` `DEBIT` 거절 |

## 해석

- **문제는 "지금 깨짐"이 아니라 "깨져도 모름"이다.** 현재 스키마는 항목 수정·삭제·`TRUNCATE`·불균형 commit을 받아들였고(H2) 서비스 흐름이 만든 데이터에는 위반이 없었다(H1, 분개 7건에서 점검 9개 모두 0, 제한 역할로 돌린 기존 시험 뒤 점검도 위반은 시험 도우미의 합성 데이터 2건뿐). 실제 버그는 찾지 못했다. 가드는 미래의 버그와 수동 SQL에 대한 보험이다.
- **추가 전용(A1)은 공짜에 가깝다**: INSERT에서는 트리거 함수가 한 번도 불리지 않는다(`pg_stat_user_functions`에 없음). 측정 차이(+0.1%(+0.001 ms), -5.9%(-0.053 ms), -11.1%(-0.074 ms))는 잡음 범위다.
- **균형 지연 트리거의 비용은 호출 수 × 호출당 인덱스 한 번 읽기다**: V7(A6)의 p50 증가는 W1 0.452 ms(+10.1%(+0.042 ms)), 처리량 -7.2%, W12 0.972 ms(+6.6%(+0.060 ms)), 처리량 +13.0%, W1 c = 4 0.650 ms(-2.7%(-0.018 ms)), 처리량 +2.7%다. 사전 등록 임계(≤ 25%이고 ≤ 0.3 ms) 안이다(`가드 비용`의 판정 표). WAL 레코드는 암과 무관하게 같다.
- **분개 트리거(A4)만으로는 부족하고 항목 트리거(A3)가 필요한 경우가 있다**(변이 M4): commit된 분개에 나중 transaction이 항목을 더해 불균형을 만드는 경우. 반대로 A3만으로는 항목 없는 분개를 못 막는다(변이 M3). 그래서 둘 다 둔다.
- **한 가지 예상 밖**: 지연 균형 트리거는 분개와 항목을 같은 transaction에서 쓰도록 강제하므로, 원시 SQL로 분개를 먼저 autocommit하던 시험 도우미 3개가 실패했다(서비스 흐름은 영향 없음). 시험 도우미를 고쳤다.
- **역할 분리는 코드 변경 없이 된다**: 런타임 역할은 DML만 받아도 앱이 끝까지 동작했고(역할 시험 3개, 기존 시험 157개 중 시험 안 DDL 외 실패 0) Flyway는 `spring.flyway.url/user/password`로 소유 계정을 쓴다(EXP-109 R1의 해법). 소유 계정이 쓰는 `DISABLE TRIGGER`를 막지는 못하므로 break-glass는 소유 계정의 권한이다.
- **복원은 절차로 증명했다**: 덤프 복원과 PITR 복원 모두 항목 해시와 점검이 일치했다. 시간과 RPO·RTO는 `백업·복원 훈련`의 표다. 같은 VM의 아카이브는 호스트 유실 보호를 증명하지 않는다.

## 결정 (ADR-0100, D-179)

1. V7(추가 전용 + 분개·항목 지연 균형)을 **기본 켜짐**으로 출시한다. 근거: H3(거절·거짓 거절 0), H4(임계 이내), 기존 시험 통과, 변이 9/10(첫 실행), 롤백 스크립트와 시험.
2. 불변식 점검과 Micrometer 게이지는 **옵트인**(기본 꺼짐)으로 출시한다. 점검 시간(22.2만 분개, n = 5): 중앙값 2.1 s.
3. 런타임 역할 분리는 스크립트와 시험으로 출시하고 **켜는 것은 배포가 정한다**(옵트인).
4. 정정은 break-glass로 하고 **역분개 분개는 만들지 않는다**(시제품 증거).
5. 백업·복원은 절차와 목표 표로 둔다. 가정 목표: RPO ≤ 24 h(야간 `pg_dump`), RTO ≤ 30분(22.2만 분개 복원 2.41 s의 여유), WAL 아카이브는 도입하지 않고 재검토 조건에 둔다.

롤백(V7): 소유 계정으로 `scripts/db/rollback/V7__undo_settlement_ledger_guards.sql`. 시험과 변이 M9가 스크립트를 지킨다. 기존 데이터 적용 순서와 정정·역할·복원 절차는 `docs/operations-guide.md`의 "원장 무결성·런타임 역할·백업과 복원" 절이다.

재검토 조건(숫자 또는 사건):

| 대상 | 조건 |
|---|---|
| V7 비용 | 운영에서 분개 INSERT p50이 가드 없는 때보다 25% 또는 0.3 ms 넘게 늘거나, 분개당 항목이 50개를 넘는 흐름이 생기면 항목 트리거(A3)를 빼고 분개 트리거(A4)만 둔다 |
| 불균형 버그의 영향 | 균형 위반 버그로 정산 반영 transaction이 롤백돼 `payment_recovery_job`이 `NEEDS_REVIEW`로 올라가는 일이 한 번이라도 생기면 원인을 고치고 이 ADR의 위험 항목을 갱신 |
| 점검 | 점검이 운영에서 위반을 1건이라도 찾거나 점검 시간이 `statement_timeout`(30 s)의 50%(15 s)를 넘으면(현재 합성 22.2만 분개 2.1 s) 주기·증분 점검을 다시 결정 |
| 정정 | break-glass를 분기당 1회 이상 쓰면 역분개 분개 기능(스키마, 명세 질의 2곳, 점검, 운영자 API)을 설계 |
| 역할 분리 | 앱이 외부 입력으로 SQL을 조립하는 경로가 생기거나 DB 접속 운영자가 둘 이상이 되면 기본 켜짐으로 바꿈 |
| 백업 | PayPal live 결제 시작 전, 또는 원장 분개 200만건 초과 시 복원 시간을 다시 재고 WAL 아카이브·pgBackRest 도입을 결정 |

## 한계

- 단일 호스트(Apple Silicon, Colima VM, 다른 에이전트와 공유). 비용은 pgbench로 SQL 모양을 재현한 것이고 앱 전체 경로(HTTP, 판매자 잠금 경합, Hikari)가 아니다. 지연 절댓값은 맥락용이고 결론은 상대 증가와 건수로 낸다.
- 비용 표에는 깨끗한 행(전후 `measure-lock.sh status`의 load1 ≤ 12, 다른 라벨 컨테이너 없음)만 넣었다. 처음 90행 중 80행은 다른 에이전트의 스택과 겹쳐 오염돼 뺐고, 잠금 구간에서 다시 채웠다. 칸마다 n을 표에 적었다.
- 합성 원장은 실제 분포가 아니다(총액 10,000~14,900원, 판매자 200명, 분개당 항목 4개). 점검 시간은 크기 두 점(2.2천, 22.2만 분개)만 있어 선형인지는 모른다.
- 복원 훈련의 WAL 아카이브는 같은 VM의 Docker 볼륨이다. 절차와 시간, 사고(논리 오류) 직전으로의 복원은 증명하지만 호스트·디스크 유실에 대한 보호는 증명하지 않는다. 그 보호는 아카이브를 다른 저장소에 두어야 성립한다.
- PayPal 대조 절차는 기존 복구 상태와 대사 기능을 읽고 쓴 제안이다. 실제 PayPal과 대조하지 않았다(Live gate LG-1, D-170). 지급이 복원 시각 앞뒤에 걸친 경우의 PayPal 쪽 멱등 동작은 확인하지 못했다.
- 모든 시험의 소유 계정은 슈퍼유저(Testcontainers 기본)다. 운영에서 소유 계정이 비슈퍼유저일 때 V7 적용(`CREATE FUNCTION`, `CREATE TRIGGER` 권한)과 `ALTER TABLE ... DISABLE TRIGGER`(테이블 소유자)는 시험하지 않았다.
- `compose.yaml`로 앱을 띄워 역할 분리를 끝까지 돌리지는 않았다. 설정은 Testcontainers 구성(시험)과 같은 환경 변수로 문서화했다.
- 이벤트 소싱·외부 원장 서비스(TigerBeetle 등)와 동기 복제는 이번 범위 밖이다(별도 시스템 도입, 단일 PostgreSQL 결정과 충돌). 동기 복제의 RPO 0은 측정하지 않았고 주장하지 않는다.

## 주장 표

| 주장 | 확신도 | 근거 |
|---|---|---|
| 현재(V6) 스키마는 항목 수정·삭제, 불균형 commit, `TRUNCATE`를 거절하지 않는다 | confirmed(문장당 n = 1, 결정적) | 현재 스키마(A0)가 받아들이는 것 |
| 분개 없는 항목은 V1 외래 키가 이미 거절한다(사전 등록의 반대 서술은 틀림) | confirmed(n = 1) | 사전 등록 정정 |
| 서비스 흐름이 만든 원장(분개 7, 항목 29)은 점검 9개를 모두 만족한다 | confirmed(n = 1 데이터 집합, 흐름 6종) | 불변식 점검 질의 |
| 주입 위반 9종을 의도한 점검이 모두 잡는다 | confirmed(9/9) | 불변식 점검 질의 |
| V7이 수정·삭제·`TRUNCATE`·불균형 commit을 거절하고 정상 흐름을 막지 않는다 | confirmed(시험 8개, 기존 시험 128개 0 실패, 변이 9/10 첫 실행) | V7 가드 |
| V7 비용이 임계 이내다(A6 p50: W1 +10.1%(+0.042 ms), W12 +6.6%(+0.060 ms), c = 4 -2.7%(-0.018 ms)) | local-only(단일 호스트, 칸마다 깨끗한 n 표 참조) | 가드 비용 |
| 제한 런타임 역할로 앱이 끝까지 동작하고 원장 수정·DDL은 거절된다 | confirmed(시험 3개 + 기존 시험 157개, 시험 안 DDL만 소유 연결 필요) | 런타임 역할 분리 |
| Flyway 전용 연결은 앱 URL 옵션을 물려받지 않는다 | confirmed(n = 1, 결정적) | 런타임 역할 분리 |
| 덤프 복원과 PITR 복원 뒤 항목 해시·점검이 일치한다 | confirmed(각 n = 1) | 백업·복원 훈련 |
| RPO ≤ 60 s(`archive_timeout`), RTO(복원 자체) 표의 값 | local-only(같은 VM 볼륨, n = 1) | 백업·복원 훈련 |
| 복원 뒤 PayPal 대조 절차가 실제 PayPal 데이터로 맞다 | not confirmed(Live gate LG-1, 문서 읽기와 기존 상태 목록에 기댄 제안) | 운영 가이드 |
| 역분개 정정 분개가 DB에서 가능하다 | confirmed(SQL 시제품) / 출시하지 않음(명세 질의가 정정 분개를 capture로 셈) | 정정 경로 |
| 합성 22.2만 분개에서 점검이 2.1 s다 | local-only(합성 분포, 크기 두 점) | 백업·복원 훈련 |

## 삭제한 파일의 내용

이 실험의 구동 파일은 한 번 쓰고 끝난 것이라 저장소에 올리지 않았다(`experiment-artifacts`). 이력에도 없다(커밋한 적이 없다). 아래가 재현에 필요한 전부다. 올린 것은 운영 파일(`V7`, `ledger-audit.sql`, `runtime-role.sql`, 롤백 스크립트)과 시험(`SettlementLedger*IntegrationTest`, `LedgerAuditMetricsIntegrationTest`)이다.

### 비용 측정 구동기(`bench`)

- 컨테이너 하나(`postgres:18.6`)에 V1~V6을 적용한 템플릿 데이터베이스 `tpl`을 만들고, run마다 `CREATE DATABASE <암> TEMPLATE tpl`로 새 데이터베이스를 만든 뒤 암의 DDL을 적용한다. 암의 DDL은 V7의 문장과 같다: A1 = 함수 `reject_settlement_ledger_mutation()`과 세 테이블의 행 트리거(`BEFORE DELETE OR UPDATE`)·문 트리거(`BEFORE TRUNCATE`) 6개, A3 = 함수 `assert_settlement_journal_balanced()`와 `settlement_entry`의 `AFTER INSERT ... DEFERRABLE INITIALLY DEFERRED` 제약 트리거, A4 = 같은 함수와 `settlement_journal`의 같은 제약 트리거, A5 = A1 + A4, A6 = A1 + A3 + A4.
- 한 run: `CHECKPOINT` → 3 s 예열(`pgbench -n -f <워크로드> -c C -j C -T 3`) → `CHECKPOINT` → `pg_current_wal_lsn()` 기록 → `pgbench -n -f <워크로드> -c C -j C -T <T> -l --log-prefix`(컨테이너 안) → `pg_current_wal_lsn()` 기록. 지표: 처리량 = 로그 줄 수 ÷ T, p50·p95 = 로그의 3번째 열(μs) 백분위, 분개당 WAL = `pg_wal_lsn_diff`를 줄 수로 나눈 값. 암 순서는 run마다 시드 고정 난수로 섞었다. run마다 직전과 직후에 `measure-lock.sh status`(load1, 다른 라벨 컨테이너)를 기록했다. 처음 run 1~5(T = 12 s, 예열 3 s)는 다른 에이전트의 라벨 스택이 겹쳐 대부분 오염돼 깨끗한 10행만 남기고, 잠금 구간에서 run 6부터 T = 6 s, 예열 2 s로 채웠다. 잠금 구간 앞부분도 상대 에이전트의 컨테이너 2개가 남아 있어 오염돼 W1(c = 1)에 run 30~33, W12(c = 1)에 run 40~41을 보충했다.
- 깨끗한 run의 기준: 전후 load1 ≤ 12이고 다른 라벨 컨테이너 없음.
- W1(capture 모양) 한 transaction: `BEGIN; INSERT INTO settlement_journal (source_order_id, source_attempt_id, seller_id, currency, gross_amount_won, recorded_at, fee_amount_won, reserve_amount_won, fee_rate_bps, reserve_rate_bps) VALUES (gen_random_uuid(), gen_random_uuid(), <판매자>, 'KRW', 10000, now(), 1000, 1000, 1000, 1000) RETURNING journal_id \gset` 다음 `settlement_entry` 4건(`PSP_CLEARING` 차변 10000, `SELLER_PAYABLE` 대변 8000, `PLATFORM_FEE` 대변 1000, `SELLER_RESERVE` 대변 1000) INSERT, `COMMIT;`. W12는 총액 30000의 분개 1건 + 판매자 3명 각각 같은 4건(항목 12건).
- 고정 건수 WAL 측정: 같은 암·워크로드를 `CHECKPOINT` 뒤 `pgbench -n -f <워크로드> -c 1 -j 1 -t 10000`(W12는 5000)으로 돌리고 시작·끝 LSN 사이를 `pg_get_wal_stats(시작, 끝)`(`CREATE EXTENSION pg_walinspect`)로 읽어 레코드 수, 크기, FPI 크기를 분개 수로 나눴다(부하와 무관). 처음에 쓴 `pg_stat_wal` 방식은 autovacuum 같은 다른 WAL이 섞여 run마다 레코드 수가 흔들려(A6 한 run이 분개당 33.9) 버렸다.

### 합성 원장 생성기(`gen_ledger.sql`)와 복원 훈련

- 판매자 200명, capture 분개 N건(항목 4개: 위 W1의 분배, 총액 10,000 + (g mod 50) × 100원, 수수료·보류 10%), 그중 10번째마다 환불 분개 1건(2,000원, 예약 `POSTED`, 항목 2개: `PSP_CLEARING` 대변·`SELLER_PAYABLE` 차변), N/100건의 확정 지급과 지급 분개(항목 4개: 환율 반올림 5, 제공자 수수료 12). V6 스키마에 넣은 뒤 V7을 적용했다(V7 아래에서 분개와 항목을 서로 다른 문장으로 commit하면 지연 균형 트리거가 거절하기 때문). N = 200,000이면 분개 222,000건, 항목 약 848,000건이다.
- `pg_dump -Fc`/`pg_restore`: `pg_dump -U atlas -Fc -f drill.dump drill`, `pg_restore -U atlas -d restored -j 1|4 --no-owner drill.dump`, 복원 뒤 `ledger-audit.sql`.
- PITR: 컨테이너를 `archive_mode=on`, `archive_command='test ! -f /archive/%f && cp %p /archive/%f'`, `archive_timeout=60`으로 띄우고, 합성 데이터 적재와 V7 적용 뒤 `pg_basebackup -D /base/data -Fp -X stream -c fast`, 기준 이후 `pgbench`(W1, c=2, 10 s)로 분개를 더하고 기준 시각과 행 수를 기록, 3 s 뒤 사고(`SET LOCAL session_replication_role = replica; DELETE FROM settlement_entry WHERE account_code = 'PLATFORM_FEE' AND amount_won = 1000;`)를 넣고 `pg_switch_wal()`로 아카이브한 다음 서버를 멈추고, 베이스 백업 복사본에 `recovery.signal`과 `restore_command = 'cp /archive/%f %p'`, `recovery_target_time = '<기준 시각>'`, `recovery_target_action = 'promote'`를 주고 새 컨테이너로 시작해 승격(`pg_is_in_recovery()`가 거짓)까지의 시간을 쟀다.

### 기타 일회성 구동

- 변이 구동기: 위 표의 변이 10개를 파일에 적용하고 `fullcheck.sh`로 대상 시험 클래스를 돌려 실패한 시험을 기록한 뒤 파일을 되돌렸다.
- 제한 역할 실행기: 기존 시험 클래스 13개의 `spring.datasource.username/password`를 런타임 역할로, `spring.flyway.url/user/password`를 소유 계정으로 바꾸고, 속성 등록 직전에 `runtime-role.sql`을 컨테이너에서 실행하고 첫 시험 전에 한 번 더 실행하며(회수 적용), 시험마다 끝난 뒤 `ledger-audit.sql`을 돌려 위반 건수를 출력하게 하는 사본 패치(저장소 밖 작업 사본에서만 적용). 원장을 비우는 시험 3곳은 슈퍼유저 연결의 `session_replication_role = replica`를 쓰는 같은 방식을 썼다.
- 트리거 함수 통계(`trackfn`): A6 데이터베이스에 `ALTER DATABASE ... SET track_functions = 'all'`을 하고 W1·W12를 `-c 1 -t 20000`으로 돌린 뒤 `pg_stat_user_functions`(호출 수, 총 시간, 호출당 시간)와 항목 4개 분개의 균형 점검 SELECT의 `EXPLAIN (ANALYZE, BUFFERS)`를 읽었다.
- 위반 데이터 적용 훈련(H8): 합성 원장(N = 2,000)에 위반 2건을 주입하고 사전 점검, V7 적용, 수정 시도, 새 불균형 분개, break-glass 정정, 정정 뒤 점검을 순서대로 실행했다(이 문서의 해당 절이 출력이다).
- 정정 분개 시제품(`rev`): V7 스키마에 `reverses_journal_id uuid UNIQUE REFERENCES settlement_journal` 열을 더하고 `settlement_journal_owner_xor`와 `settlement_entry_account_side` CHECK를 완화한 뒤, 원 분개의 항목 방향을 뒤집어 같은 transaction에서 넣었다.
