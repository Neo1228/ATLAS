# EXP-111: 원장 무결성·역할 분리·백업 복원을 실제 스키마와 실제 서비스 흐름 데이터로 측정하고 출시할 가드를 정한다

- 상태: 진행 중(사전 등록 커밋 단계). 이 절의 가설과 반증 조건은 첫 측정 전에 커밋으로 고정한다.
- 날짜: 2026-11-27
- 관련: [#714](https://github.com/Neo1228/ATLAS/issues/714), [EXP-022](EXP-022-settlement-app-write-cost.md), [EXP-045](EXP-045-settlement-balance-index.md), [EXP-109](EXP-109-resilience-reapproach.md) 후속 R1, [ADR-0052](../02-decisions/adr/ADR-0052-settlement-fee-reserve-payout.md)

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
