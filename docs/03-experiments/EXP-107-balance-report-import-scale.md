# EXP-107: 잔액 보고서 가져오기를 한도 크기로 측정한다

- 질문: Spring Batch를 도입할 근거가 되는 곳이 있는가? 후보 중 유일한 유한 데이터셋 작업인 PayPal 잔액 보고서 가져오기는 한도 크기(9,999행)에서 얼마나 걸리고, 중간에 실패하면 무엇이 남는가?
- 관련: [ADR-0096](../02-decisions/adr/ADR-0096-do-not-adopt-spring-batch.md), [Issue #689](https://github.com/Neo1228/ATLAS/issues/689), [ADR-0095](../02-decisions/adr/ADR-0095-balance-report-id-crosswalk-evidence-and-live-gate.md)
- 상태: 완료(단일 호스트, 한도 크기 n=5, 작은 크기 n=1)
- 날짜: 2026-11-26

## 사전 등록

- H1: 9,999행 가져오기는 10초 안에 끝난다. 반증 조건: 중앙값이 10초를 넘으면 Batch 도입 근거를 다시 본다.
- H2: 중간 실패는 부분 실행을 남기지 않는다(원자성). 반증 조건: 실패 뒤 실행 수나 행 수가 늘면 방어 장치가 필요하다.
- H3: 같은 보고서를 다시 올리면 새 행이 생기지 않는다. 반증 조건: 행 수가 늘면 중복 방지 결함이다.

## 구성

- 대상: `PayPalBalanceReportRunIngestionService.reconcile(byte[])`, 실제 `CsvPayPalBalanceReportParser`, 실제 `JdbcPayPalBalanceReportStore`, PostgreSQL 18.6(Testcontainers). 일치 후보 조회(`PayPalReportMatchQuery`)만 mock이다.
- 데이터: 합성 CSV. 모든 행이 `Sale`, `Record ID`가 서로 다르다. 크기 10, 1,000, 5,000, 9,999행(한도 10,000행). 9,999행은 518,147바이트(한도 10 MiB의 약 5%).
- 측정: 첫 가져오기 시간, 같은 바이트를 다시 올린 시간, 저장된 행 수, 가져오기 전후 힙 사용량 차(`System.gc()` 뒤 측정, 거친 값).
- 실패 주입: 행 번호 500에서 예외를 던지는 `BEFORE INSERT` 트리거를 걸고 2,000행 보고서를 올린다. 저장소는 Spring 컨텍스트(`@EnableTransactionManagement`, `DataSourceTransactionManager`)에서 가져와 프록시가 적용된 상태다.
- 호스트: Apple Silicon Mac, Colima VM. 다른 에이전트의 실험 컨테이너와 같은 머신이라 load average가 9~30으로 흔들렸다.
- 실행: `./gradlew :atlas-application:test --tests <임시 클래스>`. 9,999행 값 5개는 서로 다른 gradle 실행(컨테이너와 JVM 새로)에서 얻었다.

## 결과

| 행 수 | 첫 가져오기(ms) | 다시 올림(ms) | 저장된 행 | 힙 증가(MiB) | n |
|---|---|---|---|---|---|
| 10 | 119 | 140 | 10 | 4 | 1 |
| 1,000 | 553 | 230 | 1,000 | 25 | 1 |
| 5,000 | 1,783 | 356 | 5,000 | 27 | 1 |
| 9,999 | 2,901 / 2,119 / 3,579 / 913 / 1,084 | 301 / 605 / 362 / 339 / 382 | 9,999(5/5) | 60 / 59 / 67 / 12 / 62 | 5 |

9,999행 첫 가져오기 실행 시점의 load1: 약 9, 15, 28, 30, 28(실행 순서대로, 일부는 가져오기 시작 직후 기록). 중앙값 2,119ms, 범위 913~3,579ms.

| 실패 주입 | 가져오기 전 | 가져오기 후 |
|---|---|---|
| 실행 수(`paypal_balance_report_run`) | 4 | 4 |
| 행 수(`paypal_balance_report_row`) | 16,009 | 16,009 |
| 던져진 예외 | | `UncategorizedSQLException` |

저장소가 프록시임을 확인했다(`AopUtils.isAopProxy` true).

## 해석

- H1 지지: 중앙값 2.1초, 최대 3.6초(n=5, 부하가 높은 호스트).
- H2 지지: Spring 트랜잭션 안에서 500행째 실패 뒤 실행 수와 행 수가 그대로다(n=1).
- H3 지지: 다시 올린 보고서는 `duplicateUpload=true`이고 저장 행 수가 늘지 않았다(크기 4종, 각 n=1~5).
- 정정할 것: 처음 실패 주입은 저장소를 `new`로 만들어 `@Transactional`이 적용되지 않았고, 실행 +1, 행 +255가 남았다. 이것은 시험 도구의 문제이며 운영 코드 결함이 아니다. 운영은 프록시된 빈이라 원자적이다. 다만 `JdbcPayPalBalanceReportStore`를 프록시 없이 만드는 기존 통합 테스트는 원자성을 증명하지 못한다(아래 후속).
- 결론: Batch의 청크 재시작이 아낄 시간은 최악 약 4초 이하이고 실패 시 남는 상태가 없어 도입 근거가 없다.

## 한계

- 합성 CSV다. 실제 PayPal 보고서의 행 구성(수수료 행, 다중 통화)과 일치 후보 조회 비용은 반영하지 않았다(일치 후보 조회는 mock).
- 호스트 부하가 높고 흔들려 시간은 맥락용이다. 힙 증가는 GC 시점에 흔들려(12~67MiB) 거친 값이다.
- 실패 주입은 한 지점(500행째 삽입)에서 n=1이다. 다른 지점(파싱 단계, 실행 행 삽입 직후)의 실패는 시험하지 않았다.
- 한 번에 하나의 가져오기만 측정했다. 같은 보고서를 동시에 올리는 경쟁은 이번 범위가 아니다.
- Spring Batch 자체는 설치하거나 실행하지 않았다. 비교는 현재 구현의 측정과 공식 문서 요약에 기댄다.

## 후속

- 후속 1: 원자성 회귀 테스트가 프록시 없는 저장소로 돌고 있어 약하다. 필요하면 Spring 컨텍스트 기반으로 올린다(이 PR에서는 하지 않음, 기존 테스트가 이미 반복 업로드 멱등성을 검증한다). 재검토 조건: 저장소 저장 로직을 바꿀 때.

## 삭제한 파일의 내용

- 임시 측정 클래스 `BalanceReportImportScaleExperiment`(약 130줄)를 `atlas-application/src/test/java/com/atlas/experiment/`에 복사해 한 번 실행하고 지웠다(PR에 올리지 않았다). 로컬 사본은 작성자의 `~/atlas-local-experiment-drivers/`에 있다.
- 동작: Testcontainers PostgreSQL 18.6을 띄우고 `BaselineSchema`를 적용한다. 크기별로 합성 CSV를 만들어 `reconcile`을 두 번 호출하며 시간, 저장 행 수, 힙 차를 기록한다. 실패 주입 단계는 `fail_row_500` 트리거 함수를 만들고 Spring 컨텍스트에서 프록시된 저장소로 2,000행 보고서를 올려 실행 수와 행 수 변화를 센다. 결과는 `/tmp` 파일에 쓴다.

## 주장 표

| 주장 | 확신도 | 근거 |
|---|---|---|
| 9,999행 가져오기 중앙값 2.1초(0.9~3.6초) | confirmed(n=5, 부하 높음) | 위 표 |
| 중간 실패 시 부분 실행이 남지 않는다 | confirmed(n=1, 한 지점) | 위 표 |
| 같은 보고서 재업로드는 행을 늘리지 않는다 | confirmed | 위 표 |
| 처음 실패 주입의 부분 상태는 도구 문제였다 | confirmed | 프록시 적용 뒤 0 변화 |
| Spring Batch가 이 저장소에 이점이 없다 | 판단(근거: 측정 + 코드 읽기 분류) | ADR-0096 |
| 실제 PayPal 보고서에서도 같은 시간이다 | not confirmed | 합성 CSV만 사용 |
| Spring Batch 6 동작 상세 | not confirmed | 직접 시험하지 않음 |
