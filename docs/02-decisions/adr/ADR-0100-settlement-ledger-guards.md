# ADR-0100: 원장에 추가 전용·분개 균형 가드를 DB에 두고, 런타임 역할 분리·불변식 점검·복원 절차는 옵트인으로 둔다

- 상태: Accepted(사용자 승인 전 에이전트 제안; 승인 여부는 확인하지 못함)
- 날짜: 2026-11-27
- 관련 결정: [ADR-0052](ADR-0052-settlement-fee-reserve-payout.md)(수수료·보류·지급 원장), [ADR-0099](ADR-0099-location-call-bulkhead-opt-in.md)(옵트인 기본값 규칙), D-170(Live gate LG-1)
- 근거: [EXP-111](../../03-experiments/EXP-111-ledger-integrity-durability.md), 이슈 [#714](https://github.com/Neo1228/ATLAS/issues/714), [EXP-109](../../03-experiments/EXP-109-resilience-reapproach.md) 후속 R1

## 문제

원장(`settlement_journal`, `settlement_entry`, `settlement_seller_allocation`)에서 DB는 항목 수정·삭제·`TRUNCATE`와 차변≠대변인 분개의 commit을 오류 없이 받아들였다(EXP-111 H2: 문장 5종 중 4종 성공, 분개 없는 항목만 V1 외래 키가 거절). 앱과 마이그레이션이 한 계정을 쓰므로 앱이 침해되면 같은 일이 가능하고, 위반을 찾는 점검과 백업·복원 절차도 없었다. 실제 서비스 흐름이 만든 데이터에는 위반이 없었다(H1). 즉 지금 깨진 데이터가 있어서가 아니라, 깨져도 알 수 없고 막을 수 없는 구조가 문제다.

## 결정

1. **마이그레이션 V7을 기본 켜짐으로 출시한다**: 세 원장 테이블의 `UPDATE`·`DELETE`·`TRUNCATE`를 트리거가 거절하고(V1의 PayPal 보고서 reject 트리거와 같은 패턴), 분개를 넣은 트랜잭션은 commit 때 항목이 한 건 이상이고 차변 합 = 대변 합이어야 한다(`DEFERRABLE INITIALLY DEFERRED` 제약 트리거: 분개 INSERT에 하나, 항목 INSERT에 하나). 기존 행은 검사하지 않는다.
2. **불변식 점검을 옵트인으로 출시한다**: `ledger-audit.sql`(읽기 전용 9개 점검)을 앱이 `atlas.settlement.ledger-audit.enabled=true`(기본 `false`)일 때 주기(기본 `PT1H`)로 돌려 `settlement.ledger.audit.violations{check}` 게이지로 노출한다. 같은 파일을 운영자가 `psql -f`로 쓴다.
3. **런타임 역할 분리는 스크립트와 시험으로 출시하고 기본은 끄지 않는다(켜는 것이 옵트인)**: `scripts/db/runtime-role.sql`이 DML만 가진 `atlas_runtime`을 만들고 Flyway는 `spring.flyway.url/user/password`의 소유 계정으로 돌린다. 코드 변경도 새 설정 키도 없다.
4. **정정은 소유 계정의 break-glass(한 트랜잭션에서 트리거 끄기·수정·켜기)로 하고 역분개 분개는 만들지 않는다.** 절차와 시험이 있고 근거는 EXP-111의 시제품이다.
5. **백업·복원은 절차와 목표 표로 문서화하고 자동화하지 않는다.** 아래 RPO/RTO 표를 `✓ 결정(가정)`으로 둔다.

기본값 규칙: V7은 유효한 쓰기만 통과시키고 유효하지 않은 쓰기(수정·삭제·불균형)만 거절하므로 기본 켜짐이 동작을 바꾸는 곳은 서비스 흐름이 아니라 위반뿐이다(H1·H3: 서비스 흐름 거짓 거절 0). 점검은 원장 전체를 읽는 비용이 있어 기본 꺼짐이다. 역할 분리는 배포 구성(계정, 비밀)이 필요해 옵트인이다.

## 근거 (EXP-111, 단일 호스트, PostgreSQL 18.6)

| 측정 | 값 |
|---|---|
| 현재 스키마가 받아들이는 위반(H2) | 항목 수정, 항목 삭제, 불균형 commit, `TRUNCATE` 4종 성공(문장당 n = 1), 분개 없는 항목만 V1 외래 키가 거절 |
| 서비스 흐름 데이터의 점검(H1, H1n) | 분개 7·항목 29에서 점검 9개 모두 0, 주입 9종 9/9 검출 |
| V7이 거절하는 것(H3) | 시험 8개 통과: 6종 문장 거절, 불균형·항목 없는 분개 commit 거절, 정상 흐름 거짓 거절 0, 기존 시험 128개 0 실패(수정 필요했던 시험 도우미: 삭제 3곳 + 분개 우선 commit 3개) |
| V7 비용(H4, 깨끗한 run, 단일 호스트) | p50 증가 W1 +10.1%(+0.042 ms), W12 +6.6%(+0.060 ms), c = 4 -2.7%(-0.018 ms). 호출당 `pg_stat_user_functions` 값은 EXP-111. WAL 레코드 암과 무관 |
| 변이(시험이 가드를 지키는가) | 10개 중 첫 실행에서 9개 사망, 살아남은 1개(M8)는 시험 보강 뒤 사망 |
| 런타임 역할(H5, H6) | 시험 3개 통과, 12문장 42501, Flyway 전용 연결은 URL `statement_timeout` 미상속, 기존 시험 157개 중 시험 안 DDL만 소유 연결 필요 |
| 복원(H7) | 덤프 `pg_restore -j 4` 2.41 s, 덤프 65 MB, PITR 승격까지 4.21 s, 항목 해시 일치 True, 점검 2.1 s |
| 위험 | 균형 위반 버그는 정산 반영 transaction 전체를 롤백한다. 이전에는 조용히 commit됐다. 복구 job이 재시도하다 `NEEDS_REVIEW`로 올린다(기존 시험 `settlementFailureRollsBack...`가 같은 롤백 경로를 이미 확인) |


## 대안과 기각 사유

| 대안 | 기각·채택 이유 |
|---|---|
| 아무것도 하지 않음(현재) | 기각. 수정·삭제·불균형 commit을 DB가 받아들이고(H2) 탐지 수단이 없다. |
| 주기 점검만(탐지 전용) | 단독은 기각. 쓰기 비용은 없지만 거절이 없어 위반이 점검 주기만큼 남는다. 가드와 함께 두 번째 겹으로 옵트인 채택(소유 계정이 우회한 위반을 잡는다: H1n의 주입은 모두 `session_replication_role = replica` 우회). |
| 역할 분리(REVOKE)만 | 단독은 기각. 소유 계정(Flyway, 운영자)은 그대로 수정할 수 있고 앱이 소유 계정을 쓰는 구성에서는 보호가 없다. V7과 함께 이중 방어로 옵트인 채택. |
| 추가 전용 트리거만 | 단독은 기각. 소유자가 `DISABLE TRIGGER`로 우회할 수 있고(의도한 break-glass) 불균형 commit은 막지 못한다. 역할 분리가 그 빈틈을 닫는다. |
| 분개 단위 균형 트리거(A4)만 | 기각. commit된 균형 분개에 나중 transaction이 항목을 더해 불균형으로 만드는 경우를 못 막는다(변이 M4가 이 시험을 지목). 항목 단위 트리거(A3)를 함께 둔다. 비용은 근거 절의 표. |
| 역분개 분개 기능 | 보류. 스키마(CHECK 두 개와 열)와 명세 질의 2곳, 점검, 운영자 API가 함께 바뀌어야 하고 시제품에서 명세 합계가 정정 분개를 capture로 센 것(20,000 대 10,000)이 확인됐다. 재검토 조건에 둔다. |
| 이벤트 소싱·외부 원장 서비스 | 범위 밖. 별도 시스템 도입이고 단일 PostgreSQL 결정과 충돌한다. |
| 동기 복제·HA | 측정하지 않았다. RPO 0을 주장하지 않는다. |
| pgBackRest·WAL-G | 이번에는 `pg_basebackup` + `archive_command`로 절차와 복원 시간을 증명했다. 도구 도입은 재검토 조건(분개 200만건)에서 비용을 비교한다. |


## 롤백

- V7: `scripts/db/rollback/V7__undo_settlement_ledger_guards.sql`을 소유 계정으로 한 트랜잭션에서 실행한다(트리거 8개와 함수 2개를 지우고 데이터는 건드리지 않으며 마지막 줄이 `flyway_schema_history`의 V7 행을 지운다). 시험(`documentedRollbackScriptRestoresThePreV7Behavior`)이 실행 뒤 수정과 불균형 commit이 다시 가능함을 확인하고, 변이(M9)가 스크립트의 한 줄이 빠지면 시험이 실패함을 보였다. 앱을 V7 이전 이미지로 되돌리는 경우 Flyway 11 기본 `ignoreMigrationPatterns=*:future`가 V7 이력을 경고로 넘긴다.
- 점검: `ATLAS_SETTLEMENT_LEDGER_AUDIT_ENABLED=false`(기본).
- 역할 분리: 앱 계정을 소유 계정으로 되돌리고 `SPRING_FLYWAY_*`를 지운 뒤 `DROP OWNED BY atlas_runtime; DROP ROLE atlas_runtime;`.

## 재검토 조건

| 대상 | 조건 |
|---|---|
| V7 비용 | 운영에서 분개 INSERT p50이 가드 없는 때보다 25% 또는 0.3 ms 넘게 늘거나, 분개당 항목이 50개를 넘는 흐름이 생기면 항목 트리거(A3)를 빼고 분개 트리거(A4)만 둔다 |
| 불균형 버그의 영향 | 균형 위반 버그로 정산 반영 transaction이 롤백돼 `payment_recovery_job`이 `NEEDS_REVIEW`로 올라가는 일이 한 번이라도 생기면 원인을 고치고 이 ADR의 위험 항목을 갱신 |
| 점검 | 점검이 운영에서 위반을 1건이라도 찾거나 점검 시간이 `statement_timeout`(30 s)의 50%(15 s)를 넘으면(현재 합성 22.2만 분개 2.1 s) 주기·증분 점검을 다시 결정 |
| 정정 | break-glass를 분기당 1회 이상 쓰면 역분개 분개 기능(스키마, 명세 질의 2곳, 점검, 운영자 API)을 설계 |
| 역할 분리 | 앱이 외부 입력으로 SQL을 조립하는 경로가 생기거나 DB 접속 운영자가 둘 이상이 되면 기본 켜짐으로 바꿈 |
| 백업 | PayPal live 결제 시작 전, 또는 원장 분개 200만건 초과 시 복원 시간을 다시 재고 WAL 아카이브·pgBackRest 도입을 결정 |
