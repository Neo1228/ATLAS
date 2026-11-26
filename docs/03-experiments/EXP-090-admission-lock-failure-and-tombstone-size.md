# EXP-090: 잠금 실패가 어떤 예외로 올라오는가, 한 Seller-SKU join이 얼마나 느린가, admission ticket 행이 얼마나 자라는가

> 정리 안내: Part A 실행 파일(`load-tests/admission-lock-probe/`)은 저장소 오염을 막으려고 삭제했다. 동작과 실행 방법은 이 문서 끝 "삭제한 파일의 내용" 절에 적었다. 파일은 커밋 `546d8002` 이후 이력에서 복원할 수 있다.

**상태:** 로컬 합성 측정 · 운영 부하/SLA 근거 아님 · 값은 맥락으로만 사용

## 질문

[ADR-0083](../02-decisions/adr/ADR-0083-admission-operational-values-and-lock-failure.md)이 `lock_timeout` 값과 잠금 실패 응답, tombstone 정리 여부를 정하려면 세 가지를 알아야 한다.

1. PostgreSQL이 낸 잠금 대기 초과(55P03)와 교착(40P01)이 Spring을 거치면 어떤 예외가 되고, 지금 HTTP 응답은 무엇인가.
2. 한 Seller-SKU에 join이 몰릴 때 지연은 얼마인가. `lock_timeout`을 그 값보다 크게 잡아야 정상 경합이 503이 되지 않는다.
3. `admission_ticket` 행 하나는 인덱스까지 몇 바이트이고, 정리하려면 어떤 문장이 얼마나 걸리는가.

예상(코드 읽기에서, 측정 전에는 확인하지 않음): `AccountAuthenticationIntegrationTest`가 JdbcTemplate 경로의 55P03을 `UncategorizedSQLException`으로 단정하므로 JPA 경로와 예외 종류가 다를 것이고, `GlobalExceptionHandler`에는 잠금 계열 예외 처리가 없어(`git grep`으로 `Lock`·`Deadlock` 0건) 둘 다 500일 것이다.

## 환경과 방법

- 호스트: macOS 27.2.1 arm64, Colima Docker VM(8 CPU). 다른 에이전트의 Gradle·Testcontainers가 같은 VM에서 동시에 돌았다. 이 문서를 쓰던 시점의 1분·5분·15분 load average는 14.2·15.4·13.5였다(측정 순간의 값이 아니다). 그래서 시간 값은 맥락이고, 결론은 종류와 개수에 둔다.
- PostgreSQL 18.6(Testcontainers·docker, image 기본 설정), 앱은 실제 `AdmissionServiceImpl`·`CampaignServiceImpl`·JPA·JdbcTemplate. Hikari는 `application.yml` 기본(풀 10, connection-timeout 500 ms)이다.
- Part A: 삭제한 `load-tests/admission-lock-probe/run.sh`가 harness(`AdmissionLockProbeExperiment.java`)를 test 소스에 임시로 복사해 실행하고 지웠다(아래 절). 한 번 실행(예외 종류는 n=1, 지연은 아래 표본 수). 일반 `check`에 들어가지 않는다.
- Part B: `EXP-090-admission-lock-failure-and-tombstone-size.sql`을 `postgres:18.6` 컨테이너(image 기본 설정)에서 3회 실행. 매번 테이블을 새로 만든다.

## 결과 A: 잠금 실패는 어떤 예외인가 (n=1)

| 상황 | Spring 예외 | SQLState | 현재 HTTP 응답(코드 읽기, 변경 전) |
|---|---|---|---|
| JdbcTemplate 경로 `lock_timeout` (`QueueRepository.lockCounter`, join 안) | `UncategorizedSQLException` | 55P03 | 500 INTERNAL_ERROR (`handleUnexpected`) |
| JPA 경로 `lock_timeout` (`CampaignService.close`의 `findWithWriteLock`) | `CannotAcquireLockException`(cause `LockTimeoutException`) | 55P03 | 500 |
| 교착(두 Seller-SKU counter를 반대 순서로 잠금) | 한 트랜잭션은 성공, 다른 하나는 `PessimisticLockingFailureException` | 40P01 | 500 |
| `statement_timeout` | `QueryTimeoutException` | 57014 | 503 + Retry-After (이미 매핑됨) |

- 변경 전 응답은 `GlobalExceptionHandler`를 읽어 정한 것이다. 변경 전 코드에서 실제 HTTP로 재현하지는 않았다. 변경 후에는 `LockFailureMappingIntegrationTest`가 실제 PostgreSQL 예외를 만들어 503을 확인한다(변경 전 핸들러에서의 실패 여부는 PR 본문에 적는다).
- 교착은 PostgreSQL이 한쪽만 중단했다(`deadlock_timeout`은 기본 1초 설정값이며 소요 시간은 재지 않았다).
- `join`이 잠금을 쥔 다른 연결 때문에 막혔을 때 `lock_timeout` 500 ms로 끊기기까지 750 ms가 걸렸다(n=1, 트랜잭션 시작·롤백 포함).

## 결과 A: 한 Seller-SKU join 지연 (공유 호스트)

| 호출 방식 | 표본 | p50 | p95 | p99 | 최대 | 완료율 |
|---|---:|---:|---:|---:|---:|---:|
| 직렬 1 스레드 | 300 | 41.9 ms | 125.3 ms | 194.6 ms | 386.2 ms | — |
| 동시 10 스레드 × 30 | 300 | 253.5 ms | 715.4 ms | 998.5 ms | 1,435.6 ms | 전체 9.4 s, 약 32/s |
| 동시 30 스레드 × 30 | 900 | 350.4 ms | 853.2 ms | 1,363.9 ms | 1,885.6 ms | 전체 12.8 s, 약 70/s |

- 세 실행 모두 오류 0건(join 예외 없음).
- 동시 호출 지연에는 Hikari 풀(10개) 대기와 한 Seller-SKU counter 행 잠금 대기가 함께 들어 있다. 둘을 나누어 재지 않는다(결정). 이유: 위 수치는 `lock_timeout` 하한을 정하는 데만 쓰였고 분해해도 그 결정이 바뀌지 않는다. EXP-092의 12회 재측정(G9)에서 동시 30 스레드의 오류는 전부 풀 대기(`CannotCreateTransactionException`)였고 `lock_timeout` 오류는 0건이어서 풀 대기가 주된 대기임을 간접으로 보인다. 대안: Hikari `pendingThreads`·`usage` 메트릭과 `pg_locks` 대기 시간을 같은 실행에서 기록. 재검토 조건: 운영에서 join p99가 2초를 넘거나 풀 대기 시간 메트릭이 연결 사용 시간의 50%를 넘을 때.
- join 한 번이 직렬에서도 p50 41.9 ms인 것은 공유 호스트 부하와 매 join의 만료 검사·counter 잠금·삽입이 함께 든 값이다. 원인별 분해는 하지 않는다(결정). 이유: 이 값은 시간이고 호스트 부하가 폭을 만든다(EXP-092 G8: 같은 코드의 실행 간 폭 200% 이상). 대안: 전용 호스트에서 `pg_stat_statements`로 문장별 평균 시간을 분해. 재검토 조건: 부하가 낮은 전용 호스트가 생겨 직렬 p50이 20 ms 아래로 안정되는지 확인할 수 있을 때.

## 결과 B: admission_ticket 크기와 정리 문장 비용 (n=3, 1,000,000행)

| 항목 | 값 | 비고 |
|---|---:|---|
| heap | 215,580,672 B | 3회 같음 |
| 인덱스 4개(PK, `(campaign_id, idempotency_key)` UNIQUE, `(seller_id, sku_id, sequence)` UNIQUE, 만료 부분 인덱스) | 256.9~257.1 MB | |
| 행당 합계 | 472.6~472.8 B | heap 215.6 B + 인덱스 약 257 B |
| 30일 넘고 주문이 가리키지 않는 EXPIRED·CHECKOUT_FAILED | 450,059~450,064행 | 상태 분포·시각 분포는 합성 입력이다(80% EXPIRED, 10% FAILED, 10% SUBMITTED, 60일에 고르게). 운영 분포는 외부 입력이 필요하다(위 한계 절) |
| A: 새 인덱스 없이 batch 500 삭제(정리할 행이 있을 때) | 4.7~19.4 ms | PK(`ticket_id`) 순서로 가장 오래된 행부터 찾는다. 3,537 buffer |
| C: 부분 인덱스 `(created_at, ticket_id) WHERE status IN (EXPIRED, CHECKOUT_FAILED)`, 정리할 행이 있을 때 | 1.3~7.3 ms | 인덱스 35 MB |
| D: 부분 인덱스 있음, 정리할 행이 없는 정상 상태 | 0.37~10.3 ms | 10~20 buffer |
| B: 새 인덱스 없음, 정리할 행이 없는 정상 상태 | 1,426~1,822 ms | 549,934행을 모두 필터에서 버린다. 24,272~27,995 buffer read |

- `purchase_order_stub`은 실제 `purchase_order`의 `ticket_id UNIQUE`(FK 없음)와 같은 모양의 5만 행 모의 테이블이다. 실제 `purchase_order`를 쓴 측정이 아니다.
- 이 스크립트의 첫 판은 같은 트랜잭션 안에서 후보를 지우고 곧바로 쟀다. 지운 행의 dead tuple까지 훑어 정상 상태 비용이 0.64~1.5초로 부풀었다. 지금 판은 지운 뒤 커밋하고 `VACUUM`한 다음 잰다(그 오류와 수정은 이 문서에 남긴다).

## 해석과 한계

- **예외 종류가 두 갈래(측정).** 55P03은 JdbcTemplate에서는 `UncategorizedSQLException`, JPA에서는 `CannotAcquireLockException`이다. SQLState를 보고 분류하지 않으면 한쪽이 새어 나간다.
- **한 Seller-SKU의 join 처리량 상한은 약 70/s(이 호스트, 풀 10, 30 호출자).** 최대 지연 1.9초가 `lock_timeout`의 하한 근거다(2초는 이 측정의 최댓값과 가까워 정상 경합이 끊길 수 있고, 5초는 약 2.6배).
- **행 크기는 약 473 B(측정).** 1 GiB는 약 227만 행이다. EXP-086 주 격자의 구매자 300명이 만드는 ticket 300~645건/판매를 곱하면 대략 3,500~7,500번의 판매다(곱셈이며 실측 운영 값이 아니다).
- **정리 문장은 새 인덱스 없이는 정상 상태마다 표 전체를 훑는다(측정).** 550k행에서 1.4~1.8초다. 행 수에 비례해 늘 것이라는 외삽은 하지 않았다.
- 한계: 단일 호스트·공유 VM, Colima 기본 디스크. 합성 데이터(실제 ticket 상태 분포가 아님; 결정: 합성 분포를 유지한다. 필요한 외부 입력은 운영 `admission_ticket`의 상태별 행 수와 `created_at` 분포이고, V1은 행당 크기(약 473 B)만 근거로 삼는다. 재검토 조건: 운영 DB에서 30일 넘은 종료 상태 ticket 비율이 합성 가정(약 45%)과 20%p 넘게 다를 때). 교착은 테스트가 만든 두 행 교착 한 가지다. Flyway와 `lock_timeout`의 상호작용은 아래 "EXP-105 종결 결과"의 G11에서 앱 두 대를 동시에 띄워 실행했다. 원자료는 저장소에 두지 않는다(EXP-033과 같은 정책). 표가 정본이다.

## 재현

Part A의 `load-tests/admission-lock-probe/`는 삭제했다. 실행하려면 먼저 커밋 `546d8002` 이후 이력에서 복원한다(동작은 이 문서 끝 "삭제한 파일의 내용" 절). Part B는 이 저장소의 `.sql`로 그대로 실행된다.

```sh
# Part A (Testcontainers, 약 1~2분)
load-tests/admission-lock-probe/run.sh /tmp/atlas-admission-lock-probe.txt
# Part B (컨테이너에는 label을 붙이고 끝나면 지운다)
docker run --rm -d --label atlasexp=g1 -e POSTGRES_PASSWORD=x --name g1-pg postgres:18.6
docker exec -i g1-pg psql -U postgres -v ON_ERROR_STOP=1 < docs/03-experiments/EXP-090-admission-lock-failure-and-tombstone-size.sql
docker rm -f g1-pg
```

## 삭제한 파일의 내용

이 절은 저장소에서 지운 Part A 하네스의 동작을 문서에 직접 옮긴 것이다. 위 본문과 구분하려고 따로 둔다.

- 목적: 잠금 실패 예외 종류와 한 Seller-SKU join 지연 측정(EXP-090 Part A).
- 구성: PostgreSQL 18.6 컨테이너를 Testcontainers가 띄운다. 하네스(`AdmissionLockProbeExperiment.java`)는 `check`에 들어가지 않도록 `run.sh`가 `atlas-application/src/test/java/com/atlas/experiment/`에 임시 복사해 실행하고 지웠다.
- 실행: `./gradlew --no-daemon --no-build-cache :atlas-application:cleanTest :atlas-application:test --tests 'com.atlas.experiment.AdmissionLockProbeExperiment'`(JDK 25, `DOCKER_HOST` 필요). 출력 파일은 환경 변수 `PROBE_OUT`, 기본 `/tmp/atlas-admission-lock-probe.txt`.
- 결과 수치와 표본 수는 위 본문 표가 정본이다.

## EXP-105 종결 결과

재실행 표기: 아래 G11은 이 에이전트가 직접 실행했다. 컨테이너·볼륨은 `atlasexp=t5db` 라벨을 붙이고 끝에 지웠다. 도구 설치 0건(PostgreSQL 18.6 이미지, 이미 있는 앱 jar). 원자료(앱 로그, psql 출력)는 저장소에 올리지 않았고 집계는 아래 표가 전부다.

### G11: 두 앱 인스턴스가 빈 DB에 동시에 기동할 때 Flyway와 `lock_timeout=5000`

가설(G11): 두 인스턴스가 같은 빈 DB에 동시에 기동해도 Flyway의 advisory lock이 마이그레이션을 직렬화하므로 둘 다 기동하고 `flyway_schema_history`는 한 벌이다. 반증 조건: 한쪽이 기동 실패하거나 이력이 중복·실패 행을 가짐.

방법: 컨테이너 `postgres:18.6` 하나, 반복마다 새 데이터베이스를 만들고 앱 jar(`origin/main`의 `bootJar`)를 두 개 프로세스로 동시에 시작한다. 연결 URL에 `options=-c statement_timeout=30000 -c lock_timeout=5000`(운영과 같은 값)을 붙였다. 서로 다른 포트, `ATLAS_JWT_ALLOW_EPHEMERAL_KEY=true`. 각 반복에서 두 앱 로그의 `Started AtlasApplication` 여부, 종료 뒤 `flyway_schema_history`의 행 수·최대 `installed_rank`·실패 행 수, 로그의 `Migrating schema` 건수, 잠금 오류 문구(`55P03`, `lock timeout`, `advisory lock`) 건수를 센다.

| 반복 | 앱 A 기동 | 앱 B 기동 | 이력 행 수 / 최대 rank / 실패 행 | `Migrating schema` 로그(A/B) | 잠금 오류 문구(A/B) |
| --- | --- | --- | --- | --- | --- |
| 1 | 성공 | 성공 | 5 / 5 / 0 | 5 / 0 | 0 / 0 |
| 2 | 성공 | 성공 | 5 / 5 / 0 | 0 / 5 | 0 / 0 |
| 3 | 성공 | 성공 | 5 / 5 / 0 | 5 / 0 | 0 / 0 |
| 4 | 성공 | 성공 | 5 / 5 / 0 | 0 / 5 | 0 / 0 |
| 5 | 성공 | 성공 | 5 / 5 / 0 | 5 / 0 | 0 / 0 |

결과: 5/5 반복에서 두 앱 모두 기동했고 이력은 5행(V1~V5)·실패 0이며 마이그레이션 로그는 매번 한 앱에만 나왔다(A 3회, B 2회. 앱 A를 먼저 시작했는데도 B가 마이그레이션한 반복이 있어 어느 쪽이 잠금을 얻는지는 고정되지 않는다). 잠금 관련 오류 문구는 0건이다. 가설은 반증되지 않았다(0/5, 상한 3/5 = 60%: 표본이 작다). 반복 시작 후 3초 동안 0.1초 간격 30표본으로 `pg_stat_activity`의 advisory lock 대기를 살폈으나 대기한 세션은 0이었다. 표본 창(3초)이 앱 기동 시간보다 짧아 대기 순간을 놓쳤을 수 있고, 이 관측은 "한 앱만 마이그레이션했다"는 로그 사실로 대체해 읽는다. 앱 종료 코드는 하네스가 자식 프로세스를 `wait`하지 못해 기록되지 않았다(스크립트 결함, 판정에 쓰지 않음).

음성 대조(`lock_timeout=5000`이 Flyway를 깨뜨릴 수 있는가): 이력 표가 있는 DB에서 다른 세션이 `flyway_schema_history`에 `ACCESS EXCLUSIVE` 잠금을 20초 쥐게 하고(`pg_locks`로 잠금 보유 1건 확인) 앱을 기동했다. 앱은 5초 뒤 `canceling statement due to lock timeout`(SQLState 55P03)로 `flywayInitializer` 단계에서 기동에 실패했고 잠금 오류 문구가 9줄 나왔다(n=1, 결정적). 이 대조군이 검출기를 증명한다: Flyway 쪽의 `lock_timeout`은 실제로 기동 실패를 만든다. 결정(유형 2): 운영 설정 `lock_timeout=5000`은 유지한다. 이유: 이력 표를 5초 넘게 잠그는 정상 경로는 두 앱의 동시 기동(위 표)이 아니라 사람이 건 수동 DDL뿐이고, 그 경우 기동 실패는 잘못된 상태로 올라오는 것보다 낫다. 대안: Flyway 연결에만 `lock_timeout`을 0으로 두기. 재검토 조건: 운영 배포에서 55P03으로 기동이 한 번 실패하거나 마이그레이션 중 동시 기동이 필요한 배포 방식이 생길 때.
