# EXP-093: 반품 기간 7일과 검수 5영업일이 코드로 강제되는가 (사전 등록)

**상태:** 사전 등록 · 아직 아무것도 실행하지 않음 · 이 커밋 이후의 결과는 같은 파일의 뒤 절에 추가하고 이 절은 고치지 않는다

## 해결할 문제

[ADR-0085](../02-decisions/adr/ADR-0085-v1-scope-channel-operator-and-payout-decisions.md)는 7일·5영업일을 V1 설정 기본값으로 정했지만 법률·Seller 계약에 맞는다는 승인은 아니다(아래 G5 결정). 사전 등록 기준선 `82345acd`의 `OrderReturnServiceImpl.request`는 배송 완료 여부만 보고 `delivered_at` 경과 시간을 검사하지 않았고, PostgreSQL 경계 행렬에서 기간 밖 6/6건이 수락됐다. 현재 영향 주문 수는 외부 차단: 운영 `order_shipment`·`order_return`의 읽기 전용 집계가 제공되지 않았다. V1은 새 반품에 설정된 기간 검사를 적용한다. 재검토 조건: 운영자가 비식별 집계 입력을 제공할 때 영향 건수를 산출한다.

## 코드에서 확인한 사실 (실행 전, 코드 읽기)

- 반품 줄마다 `resolveShipment`가 정확히 하나의 DELIVERED 출고를 고른다. 출고 행에는 `delivered_at`이 있고 `status='DELIVERED'`이면 NOT NULL인 CHECK가 있다(`V1__baseline.sql`의 `order_shipment_timestamps_check`). 새 컬럼은 필요 없어 보인다.
- 수령 시각은 `order_return.received_at`에 이미 저장된다. `receive`는 이미 RECEIVED이면 행을 다시 쓰지 않는다.
- 요청은 `lockOrder`(행 잠금) 안에서 `clock.instant()`를 읽는다. `Clock`은 `ClockConfiguration`의 `Clock.systemUTC()` 빈이고 통합 테스트는 `@Primary` 조정 가능 Clock을 덮어쓴 선례가 있다(`BuyerFlowIntegrationTest`).
- 같은 idempotency key의 재전송은 상태 검사보다 먼저 기존 반품을 돌려준다(코드 읽기).

## 정의 (실행 전에 고정)

- **반품 기간:** `atlas.order.return.window`(기본 `P7D`, 정확히 168시간). 반품 줄의 출고 `delivered_at`부터 센다. **경계는 포함**이다: `요청 시각 <= delivered_at + window`이면 받는다. 마이크로초 절사된 같은 `now`로 비교한다.
- **영업일:** 월~금. 공휴일은 **지원하지 않는다**(공휴일이 끼면 마감이 실제보다 빨리 온다 = 알림이 일찍 뜨는 쪽). 날짜는 `atlas.order.return.business-zone`(기본 `Asia/Seoul`)의 달력으로 센다.
- **검수 마감:** 수령 시각의 현지 날짜에서 N영업일 뒤의 날이 마감일이고, 마감 시각은 마감일 다음 날 00:00(배타적 끝)이다. 수령일이 주말이면 다음 월요일이 1영업일이다. 마감 시각 이후(`now >= dueAt`)이고 상태가 RECEIVED이면 초과다.
- **초과의 처리:** 표시와 집계 gauge만 한다. 상태를 바꾸지 않고 자동 승인·자동 환불을 하지 않으며 늦은 검수도 막지 않는다.

## 가설

각 가설은 반증 관측, 지표, 기대값, 결정을 함께 적는다. "기준선"은 `origin/main` 코드에서, "변경 후"는 이 브랜치에서 같은 시험으로 잰다.

| ID | 가설 | 반증 관측(이것이 나오면 가설이 틀림) | 지표와 기대값 | 결정 |
| --- | --- | --- | --- | --- |
| H1 | 기준선에서 기간 검사가 없다: 배송 후 8일·30일·400일 요청도 받는다 | 기준선에서 8일·30일·400일 중 하나라도 거절됨 | 기준선 수락 6/6(지점 6개 전부) | 문제가 실재함을 확인하고 H2~로 진행. 기준선이 거절하면 문제 정의를 고친다 |
| H2 | 변경 후 기간 밖 요청은 HTTP 409와 `RETURN_WINDOW_EXPIRED`로 거절되고 부작용이 없다 | 거절 뒤 `order_return` 행이 생김, 환불 요청 행이 생김, 재고가 변함, 코드가 다름 | 7일+1초·8일·30일·400일: 거절 4/4, 부작용 0 | 구현 유지. 하나라도 부작용이면 중단 |
| H3 | 경계는 포함이다: 6일 23:59:59와 정확히 7일은 받고 7일+1초는 거절한다 | 세 지점 중 하나라도 기대와 다름 | 받음 2/2, 거절 1/1 | 포함/배타 결정을 ADR-0089에 확정. 틀리면 경계 정의를 고친다 |
| H4 | 기준 시각은 반품 줄이 속한 출고의 `delivered_at`이다(분할 출고): 출고 A(0일)·B(5일), 지금 9일이면 A 줄은 거절, B 줄은 수락, 둘을 섞은 요청은 전체 거절(부분 저장 없음) | 혼합 요청에서 행이 하나라도 생김. A 줄이 수락됨. 주문 단위 `fulfillment.delivered_at` 기준과 구분되지 않음 | 3개 요청 결과가 기대와 일치. 주문 기준이면 B 줄도 거절되었을 것(구분 가능한 시나리오) | 출고 단위 기준 확정. 틀리면 주문 단위로 재검토 |
| H5 | 이미 접수된 반품의 같은 key 재전송은 기간이 지나도 원래 응답을 돌려준다(돈 경로에서 응답 유실 뒤 재시도가 깨지지 않음) | 기간이 지난 뒤 같은 key 재전송이 409 | 재전송 1/1이 원래 반품과 같은 응답. 다른 key의 같은 줄은 거절 | 검사 위치를 idempotency 확인 뒤로 확정 |
| H6 | 시계는 행 잠금을 얻은 뒤에 읽는다: 다른 트랜잭션이 주문 행을 잡은 동안 대기한 요청은, 대기 중 시계가 경계를 넘으면 거절된다 | 대기 후 경계를 넘었는데 수락됨 | 반복 5회 모두 거절. 잠금 앞에서 시계를 읽게 바꾸면(변이) 수락되어 시험이 실패 | 시계를 읽는 위치를 규칙으로 못 박는다 |
| H7 | 같은 순간 경계에 동시 요청이 몰려도 수량 한도와 기간 검사가 모두 지켜진다: 경계 정확히에서 8개 스레드가 1개씩(수량 3) 요청하면 정확히 3개 성공, 5개는 수량 충돌이고 기간 오류는 0 | 성공 수가 3이 아님, 기간 오류가 섞임, 초과 수락 | 라운드 n=30에서 위반 0이면 위반율 상한 3/30=10%(95%, rule of three) | n이 작아 "위반이 없다"가 아니라 "10%보다 크지 않다"만 주장 |
| H8 | 영업일 마감은 주말과 금요일을 건너뛴다: 금요일 수령+5영업일=다음 주 금요일, 토요일 수령+5=다음 주 금요일, 금요일 수령+2=화요일 | 날짜 표 일치 실패 | 표 지점 전부 일치. 달력 함수는 표 기반 단위 시험이라 n은 지점 수 | 규칙 확정 |
| H9 | 마감 경계는 배타적 끝이다: 마감 시각 1초 전은 초과 아님, 마감 시각 정각은 초과 | 두 지점 중 하나라도 다름 | 일치 2/2 | 경계 확정 |
| H10 | 초과는 표시만 한다: 초과된 RECEIVED 반품은 판매자 목록에 `inspectionOverdue=true`로 나오고, 스스로 상태가 바뀌지 않고 환불이 생기지 않으며, 늦은 검수는 같은 환불 금액으로 정상 처리된다. gauge 값은 초과 건수와 같다 | 시간 경과만으로 상태·환불 행이 바뀜, 늦은 검수가 거절됨, gauge가 목록과 다름 | 초과 2건·정상 1건 시나리오에서 gauge=2, 환불 행 0, 늦은 검수 후 환불 1 | 자동 승인·자동 환불을 만들지 않는다는 ADR-0085 결정을 코드로 확인 |
| H11 | 재검수 재시도(환불 송금 실패 뒤)는 마감을 리셋하지 않는다 | `received_at`이나 `inspectionDueAt`이 재시도 뒤 달라짐 | 값 불변 | 마감 기준을 수령 시각으로 확정 |
| H12 | 설정 범위를 벗어난 값은 시작 시 거절되고, 기본 yml 값은 코드 기본값과 같다 | 범위 밖 값이 통과함. yml 기본값과 코드 상수가 다름 | 거절 지점 전부, 일치 | `AdmissionLimits`와 같은 패턴 유지 |
| H13 | 롤백: `atlas.order.return.window-enforced=false`면 이전 동작(400일 요청 수락)으로 돌아간다. 다시 켜면 거절한다 | 끈 뒤에도 거절됨. 켠 뒤에도 수락됨 | 끈 상태 수락 1/1, 켠 상태 거절 1/1 | 롤백 절차를 ADR-0089에 적는다 |

## 통제 (control)

- **변경 없음 통제:** 기간 안 요청(1일)은 기준선과 변경 후 모두 수락되어야 한다. 이것이 깨지면 정상 반품이 막히는 회귀다.
- **음성 통제(탐지기가 반드시 켜져야 하는 경우):** 기준선에서 H1의 8일·30일·400일 요청이 수락되는 것이 "탐지기가 문제를 본다"는 증거다. 시험 하네스가 거절을 못 보는 버그일 수 있으므로 변경 후에는 같은 시험이 거절을 본다는 것과 기준선에서는 수락을 본다는 것을 둘 다 보인다. 새 시험마다 규칙을 깨뜨려(변이) 실패하는지 확인하고 `cmp`로 복원을 확인한다.
- **전후 비교:** 같은 시험 본문, 같은 Testcontainers PostgreSQL 이미지(`postgres:18.6`), 같은 결제 가짜(`PayPalIntegrationTestSupport`), 같은 Clock 조작으로 기준선과 변경 후를 잰다. 시간 의존 결과는 모두 조정 가능 Clock으로 만든 값이라 실제 시계와 호스트 부하에 영향을 받지 않는다.

## 표본 크기

- 경계·영업일 가설(H2~H5, H8~H11, H13)은 결정적 함수에 대한 지점 시험이다. n은 지점 수이고 "경계에서 한 번 맞았다"가 곧 규칙의 증명은 아니므로, 각 규칙을 깨뜨리는 변이를 두어 해당 시험이 실패함을 확인한다.
- H6은 결정적 시나리오를 5회 반복한다(잠금 보유 스레드는 `CountDownLatch`로 순서를 고정). 실패 0/5의 위반율 상한은 3/5=60%라서 반복 횟수가 아니라 **변이 시험이 실패함**을 근거로 삼는다.
- H7은 동시성 확률 시험이라 n=30 라운드(라운드마다 새 주문). 0/30이면 위반율 상한은 3/30=10%이다. 이 시험은 규칙이 동시에도 깨지지 않는다는 증명이 아니라 직렬화가 `lockOrder`(`SELECT ... FOR UPDATE`)에 의존한다는 기존 구조의 확인이다.
- 시간(ms)은 공유 Mac에서 잰 값이므로 맥락으로만 적고, 결론은 개수와 불변식으로 낸다. 실행 전후에 `uptime`을 기록한다.

## 혼란 요인 목록 (사전 작성)

| 요인 | 어떻게 배제하는가 |
| --- | --- |
| 실제 시계와 Clock 조작이 섞임 | 서비스와 시험이 같은 `@Primary` 조정 가능 Clock 빈 하나를 쓴다. 시험이 `set`한 값 외에는 시계가 움직이지 않는다 |
| 마이크로초 절사로 경계가 한 칸 어긋남 | 경계 시험은 `delivered_at`을 DB에서 읽은 값에 `window`를 더해 만든다(절사 후 값 기준) |
| 첫 실행 효과(컨텍스트 기동, 컨테이너 pull) | 경계 결과에는 시간이 개입하지 않는다. 시간 값은 맥락으로만 적는다 |
| 하네스 버그(거절을 못 보는 시험) | 기준선 음성 통제와 변이 시험 |
| 이전 시험이 남긴 데이터 | 라운드와 시나리오마다 새 판매자·SKU·주문을 만든다 |
| 시간대(DST, UTC와 KST 날짜 경계) | 영업일 시험은 UTC 15:00(=KST 자정) 전후 1초를 포함한다. KST는 DST가 없다 |

## 재현

재현 명령(프로젝트 JDK 25·Testcontainers PostgreSQL 18.6; `DOCKER_HOST`는 Colima): `./gradlew --no-daemon --console=plain :atlas-application:test --tests 'com.atlas.order.OrderReturnWindowIntegrationTest'`; `./gradlew --no-daemon --console=plain :atlas-order:test --tests '*ReturnPolicyTest'`; `./gradlew --no-daemon --console=plain :atlas-application:test --tests '*ReturnPolicyDefaultsTest'`. 경계 행렬은 임시 `ReturnWindowProbeIntegrationTest`를 동일 테스트 source set에 놓고 `EXP093_OUT=<결과 파일> ./gradlew --no-daemon --console=plain :atlas-application:test --tests 'com.atlas.order.ReturnWindowProbeIntegrationTest'`로 실행했다. 기준선 코드는 `82345acd`, 변경 후 코드는 이 PR의 구현이다. race 탐침은 `EXP093_ROUNDS=30 EXP093_THREADS=8 EXP093_OUT=<결과 파일> ./gradlew --no-daemon --console=plain :atlas-application:test --rerun --tests 'com.atlas.order.BoundaryRaceRoundsIntegrationTest'`로 실행했다(라운드별 신규 주문/재고 3, 경계 8개·+1초 4개). 임시 탐침/원시 TSV는 Git에 넣지 않았고, 실행한 집계와 매개변수는 이 문서에 보존한다.

## 삭제한 파일의 내용 (집계된 결과)

### 문제와 전후 경계 행렬

**문제 한 문장:** 기준선은 배송 완료 뒤 기간 제한 없이 반품을 수락했다(경계 행렬 비통제 지점 6/6). 변경 후에는 기간 밖 요청을 거절한다. 실제 운영 영향 건수는 운영 배송·반품 테이블의 비식별 집계가 없어 외부 차단이며, V1은 측정되지 않은 발생 건수를 주장하지 않는다. 재검토 조건: 읽기 전용 집계 입력이 제공될 때.

기준선은 사전 등록 커밋 다음, 구현 커밋 이전의 `82345acd` 코드에서 실행했다. 같은 탐침·Testcontainers PostgreSQL 18.6·조정 가능 Clock으로 배송 후 나이별 7개 요청을 시도했다. 경과 시간은 배송 완료 시각 기준이다.

| 조건 | 기준선 수락/거절 (n=1/점) | 변경 후 수락/거절 (n=1/점) | 결과 |
| --- | ---: | ---: | --- |
| 1일(변경 없음 통제) | 1/0 | 1/0 | 정상 경로 유지 |
| 6일 23:59:59 | 1/0 | 1/0 | 경계 -1초 수락 |
| 7일 정각 | 1/0 | 1/0 | 경계 포함 |
| 7일+1초 | 1/0 | 0/1 | 409 `RETURN_WINDOW_EXPIRED`, 잔존 행 0 |
| 8일 | 1/0 | 0/1 | 같은 오류, 잔존 행 0 |
| 30일 | 1/0 | 0/1 | 같은 오류, 잔존 행 0 |
| 400일 | 1/0 | 0/1 | 같은 오류, 잔존 행 0 |

기준선 7/7 수락(비통제 지점 6/6)으로 문제 재현. 변경 후 정상·경계 지점 3/3 수락, 기간 밖 4/4 거절, 거절 뒤 `order_return` 행 0 및 환불 요청 행 0. 재고 불변량은 이 행렬에서 직접 세지 않았다. HTTP 통합 시험은 409와 body의 `RETURN_WINDOW_EXPIRED`를 확인했다.

기준선·변경 후 각 행렬을 같은 임시 탐침으로 한 번 더 재실행해 `uptime`과 `docker stats --no-stream` 스냅샷을 전후로 기록했다(통계는 공유 호스트 전체 컨테이너, 성능 비교에 쓰지 않음). 행렬 결과는 기준선 7/7 수락, 변경 후 3/7 수락·4/7 거절로 동일했다.

| 실행 | 1m/5m/15m load average 전→후 | 실행 중 컨테이너 수 | Docker CPU 합계 전→후 | 단일 컨테이너 최대 CPU 전→후 |
| --- | --- | ---: | --- | --- |
| 기준선 행렬 | 3.61/6.63/12.88 → 4.36/6.64/12.77 | 16 | 22.9% → 30.2% | 5.7% → 13.9% |
| 변경 후 행렬 | 4.25/6.58/12.72 → 4.47/6.52/12.59 | 16 | 15.3% → 94.7% | 4.3% → 88.5% |

변경 후 행렬의 후 스냅샷에는 공유 Docker 컨테이너 하나가 CPU 88.5%를 썼다. 공유 `docker stats`만으로 소유자·원인을 특정할 수 없어 종료·변경하지 않기로 결정했고, 시간·CPU를 효과로 해석하지 않고 DB 상태 개수만 결론에 쓴다. 재검토 조건: 통제 재실행에서 `atlasexp=h3`로 표시된 자체 컨테이너가 CPU 50%를 넘으면 그 컨테이너만 추가 조사한다.

### 분할 출고·멱등성·잠금

PostgreSQL 시험에서 출고 A만 완료된 동안은 기존의 주문 전체 배송 게이트가 요청을 막았다. A와 B 배송 완료 뒤 A의 `delivered_at + 7일 + 1초`에 A 줄은 409, B 줄은 수락, A+B 혼합은 전체 거절·행 0이었다. 같은 key 재전송은 만료 뒤에도 원래 응답, 새 key는 거절. 잠금 대기 중 시계를 경계 정각에서 +1초로 이동한 요청도 거절됐다. `pg_stat_activity`/`pg_blocking_pids`로 실제 Postgres 잠금 대기를 관측했다. 추가로 provider refund 응답을 `FAILED`로 만든 PostgreSQL 통합 시나리오에서 첫 검수는 충돌로 끝나고 `RECEIVED`가 유지됐다. 같은 검수를 재시도해 환불을 완료했으며 DB `received_at`과 Seller 응답 `inspectionDueAt` 모두 최초 값과 같았다(자세한 내용은 G1).

### 검수 마감·표시·환불

단위 시험 14개 전부 통과: 금요일+2영업일은 화요일 마감, 주말 수령+5영업일은 다음 금요일 마감, KST UTC 자정 전후, 마감 -1초/-1ns·정각·+1초, 설정 범위. 후보 조회 하한은 서울·뉴욕의 시각/영업일 21,600개 조합에서 실제 마감 전 수령 건을 빼지 않았다. PostgreSQL 시나리오의 RECEIVED 3건 중 2건 초과 시 목록 flag와 gauge=2, 상태 3건 유지·환불 0건. 늦은 검수와 기간 내 검수 모두 같은 환불 금액; 완료된 늦은 건은 초과 gauge에서 빠져 gauge=1. 재수령 재호출은 `receivedAt`/dueAt을 바꾸지 않았다.

### 동시성(n=30)·음성 통제

공유 Mac load average는 경합 전 27.52/27.89/21.52, 후 24.09/26.75/21.83(1/5/15분). 경계 8스레드/라운드 결과 매번 수락 3, 수량 충돌 5, 기간 오류 0, 기타 오류 0, 행 3. +1초 4스레드/라운드는 매번 수락 0, 기대한 기간 거절 4, 행 0. 수락 건수 min/median/max=3/3/3, 위반 0/30, rule-of-three 95% 상한 10%. 이는 공유 호스트의 국소 동시성 확인이지 성능/SLA 주장이 아니다.

**음성 통제:** 기간 검사를 제거한 M1 변이로 같은 경합 탐침을 n=5 실행. +1초 라운드 5/5에서 잘못된 수락 3건·행 3개(총 수락 15·행 15)를 잡았다. 코드 원본은 `cmp`로 복구했다.

같은 race 및 음성 통제를 다시 실행하면서 `uptime`·`docker stats --no-stream`을 전후 스냅샷으로 남겼다. 두 번째 정상 race도 n=30 전부 동일 결과였고, 음성 통제는 n=5 전부 +1초 수락 3건을 만들었다.

| 실행 | 1m/5m/15m load average 전→후 | 실행 중 컨테이너 수 | Docker CPU 합계 전→후 | 단일 컨테이너 최대 CPU 전→후 |
| --- | --- | ---: | --- | --- |
| 정상 경합 n=30 | 4.34/6.26/12.21 → 3.91/6.01/11.95 | 16 | 10.6% → 20.8% | 3.5% → 4.3% |
| 음성 통제 n=5 | 4.41/5.95/11.75 → 4.32/5.85/11.62 | 16 | 26.1% → 16.3% | 13.6% → 4.8% |

각 Docker snapshot은 다른 프로젝트의 동시 실행 컨테이너를 포함한다. 조건은 매 라운드 경계→+1초 순서로 쌍을 interleave했으나, 매트릭스 조건은 고정 순서였다. 지표는 시각·CPU가 아니라 상태 개수만이며, 서로 다른 실행의 CPU는 전후 효과로 비교하지 않는다.

### 새 테스트별 mutation verification

M0는 변경 없는 통제(22개 관련 테스트, 실패 0). 나머지 12개는 규칙을 하나씩 고의로 깨뜨렸고 12/12가 해당 시험에 실패했다. 매 실행 뒤 원본 파일을 `cmp` 및 Git diff로 복원했다.

| 변이 | 깨뜨린 규칙 | 실패 테스트 / 22 | 감지한 시험 |
| --- | --- | ---: | --- |
| M1 | 기간 검사 제거 | 5 | 경계, 잠금 대기, split, replay, rollback |
| M2 | 경계 배타화 | 2 | 경계 단위·통합 |
| M3 | 잠금 전에 Clock 읽기 | 1 | 잠금 대기 |
| M4 | 주문/최신 출고 기준 | 1 | split |
| M5 | 만료 뒤 같은 key도 거절 | 1 | 멱등 replay |
| M6 | rollback flag 무시 | 2 | rollback 단위·통합 |
| M7 | 토요일을 영업일로 처리 | 7 | 영업일 표·마감 통합 |
| M8 | 마감 정각은 미초과 | 2 | 마감 단위·통합 |
| M9 | 완료 상태도 overdue | 1 | 늦은 검수 통합 |
| M10 | 후보 조회 하한을 너무 엄격하게 | 2 | 후보 하한·gauge 통합 |
| M11 | HTTP 409 대신 400 | 1 | HTTP 경계 통합 |
| M12 | overdue 때 자동 상태 변경 | 1 | no-auto-action 통합 |
| M0 | 코드 변경 없음 통제 | 0 | 전체 통과 |

M7·M10의 실패 테스트 수가 1보다 큰 이유는 한 테스트 안의 여러 단위/통합 assertion이 같이 깨졌기 때문이다. Mutation은 실행 분기 검증이며 운영 분포의 표본이 아니다.

### 설정 조정·실제 rollback drill

- `ATLAS_ORDER_RETURN_WINDOW=P30D`로 같은 행렬 실행: 1일·6일 23:59:59·7일·7일+1초·8일·30일 정각 수락(6/7), 400일 거절(1/7). 환경변수는 테스트 프로세스에 주입했고, 서비스 재시작이 적용 조건이다.
- **실제 rollback:** `ATLAS_ORDER_RETURN_WINDOW_ENFORCED=false`로 같은 행렬 실행, Gradle exit 0 및 7/7 수락(400일 포함). 뒤이어 기본값 true로 7일+1초 이후 4/4 거절을 재확인. 영구 통합 시험에서도 동일 fixture에 flag off 수락 → on 거절. 스키마 변경이 없어 down migration은 없다.

### 혼란 요인·사전 등록과 달랐던 점·조사 경계

- 같은 코드의 변경 전/후 및 통제/음성 통제, 각 테스트의 신규 주문, DB에 저장된 `delivered_at`, 조정 가능 Clock으로 첫 실행·잔여 fixture·실제 시계를 배제했다. 시간을 성능 주장에 쓰지 않았다. Testcontainers 이미지와 PostgreSQL은 동일했다.
- 공유 호스트 load average는 위에 기록. 잠금 반복 5회의 실행 전 17.61/76.23/85.92, 후 25.44/57.58/76.84(1/5/15분)로 부하가 높고 흔들렸다. 이 때문에 지연은 비교하지 않고 잠금/결과 개수만 사용했다. host uptime은 작업 전체 전후 모두 약 9일이었다.
- H6의 최초 통합 실행은 1회였고, 사후 `--rerun` 반복 5회에서 5/5 통과. 같은 host 반복이며 독립 환경 표본이 아니다. 실패 0/5의 95% rule-of-three 상한은 60%.
- **절차 이탈:** 사전등록에서 “pre-mortem before implementation”을 약속했지만 실제로는 구현 후 위험·감지 표를 ADR-0089에 적었다. 이를 사전 분석이었다고 주장하지 않는다.
- ADR-0085의 `inspection-deadline: P7D` 대신 `inspection-business-days` 정수로 의미를 명확히 했다. 5영업일은 평일 수령이면 7 calendar days, 토요일이면 6, 일요일이면 5다. ADR-0089에 대안과 근거를 남겼다.
- 기준선은 service API 경계 행렬이며 HTTP 응답 코드는 변경 후 통합 시험에서 확인했다. 그 행렬은 재고 불변량을 측정하도록 설계되지 않았으므로 재고 효과를 주장하지 않는다. 재검토 조건: 기간 만료의 재고 영향이 의사결정에 필요해지면 별도 DB invariant 시험을 추가한다.
- **조사 중단 층:** 앱 서비스·응답·PostgreSQL 행 잠금·금전 상태에서 동작을 재현하고 수정했으므로 proxy/network, JVM GC/thread dump, OS/container/cgroup은 조사하지 않기로 결정했다. 재검토 조건: 동일 상태 전이가 수정 후에도 재현되지 않거나 DB 오류가 원인으로 관측되면 하위 계층을 연다.
- Alertmanager/Prometheus/Grafana를 포함한 tracked alert rule·receiver 정의는 `git grep` 결과 0건이다. 저장소의 `observability/prometheus.yml:5-9`는 scrape만 하고, 애플리케이션은 `ReturnInspectionMetrics.java:23`에서 gauge를 만들며 observability profile(`application-observability.yml:17-18`)에서 Prometheus endpoint를 노출한다. 실제 운영 경보는 외부 차단: 운영자의 Prometheus alert-rule 및 Alertmanager 수신자·라우팅 설정이 필요하다. V1 동작은 gauge 제공뿐이며 개별 경보 로그·수신은 하지 않는다.
- Testcontainers 컨테이너는 종료 시 정리됐고, 실행 뒤 `atlasexp=h3` label 컨테이너·볼륨·네트워크 0개를 확인했다. 원시 TSV/log/임시 탐침은 커밋하지 않고 이 문서에 집계 결과와 실행 매개변수를 보존했다.

### 전체 검증의 실패 추적 (H3 영향 분리)

- 첫 full `clean check`(`head=39b01eaa`)는 exit 1, 전체 1,069 tests 중 `LocationReservationServiceIntegrationTest`의 18개 중 6개 assertion 실패였다. 로그상 실패 메서드는 `renewsLeaseBeforeNextRemoteCallWhenFanoutConsumesHalfTheLease`, `splitOrderRestockBeyondOneLocationsClaimedQuantityLeavesNoRestockRows`, `retryScheduledDuringPollWaitsForTheNextRecoveryPoll`, `splitOrderReturnRestocksEachLocationWithItsOwnQuantitiesExactlyOnce`, `holdTooCloseToExpiryAfterTheNextLocationRespondsIsCompensated`, `recoveryClaimsEachWorkflowImmediatelyBeforeProcessing`이며, XML 결과는 보존되지 않아 실패 원인을 더 분류할 증거가 없다. H3 통합 테스트는 6/6 통과했다.

실패 주장을 최신 clean `origin/main`과 rebased H3 branch에서 분리했다. 같은 호스트·같은 Gradle 명령으로 각 worktree에서 5회씩 실행했고, 실행 순서는 main→H3, H3→main으로 교차했다.

| worktree | HEAD | 실행 | 테스트/실행 | 실패 |
| --- | --- | ---: | ---: | ---: |
| clean origin/main | `d6b4d4ed` | 5 | 18 | 0/5 |
| H3 branch | `ed9cfbf3` | 5 | 18 | 0/5 |

명령(각 worktree에서 5회): `./gradlew --no-daemon --console=plain :atlas-application:test --rerun --tests 'com.atlas.order.LocationReservationServiceIntegrationTest'`. 두 worktree의 10회는 모두 18/18 통과(0/10 실패)했다. 앞선 단독 재실행 18/18과 두 번째 clean check의 클래스 실행 18/18을 합쳐 최초 실패 뒤 후속 실행은 0/12 실패다. 클래스 실행을 표본 단위로 한 rule-of-three 95% 상한은 3/12=25%다. 같은 호스트 실행이므로 독립 호스트·CI 실패율 추정치는 아니다. [EXP-074](EXP-074-slow-test-forensics.md)·[EXP-097](EXP-097-ci-observation.md)의 `LocationFaultConvergenceIntegrationTest` 장애는 다른 테스트 클래스이며, 해당 문서의 dangling claim 서명과 이 클래스의 assertion 실패를 연결하는 증거는 없다. 따라서 이 6건의 원인은 규명되지 않았고, 기존 LocationFault/dead-DB/context 지연 문제와 같은 원인이라고 보지 않기로 결정했다. 재현되지 않은 코드 수정도 하지 않는다. 재검토 조건: 다음 10회 clean-worktree 단독 실행에서 1회라도 실패하면 XML·실패 DB 행·thread dump를 보존해 원인을 분류한다. 공유 호스트 지표는 위와 같으며 성능/SLA 결론에는 사용하지 않는다.

이후 최신 main `d6b4d4ed`로 재기반한 H3 branch `9cecd581`에서 두 번째 `clean check`를 실행했다: exit 0, 184 XML suites·1,099 tests·실패 0 (`BUILD SUCCESSFUL`). 최초 6개 실패는 0/12 후속 클래스 실행과 이 clean check 모두에서 재현되지 않았다. 이로써 원인을 규명한 것은 아니며, 현 시점 결정은 코드 변경 없이 보류하고 위 재검토 조건을 적용하는 것이다.

## 대안 비교 요약

세부 비용·위험·steel-man은 [ADR-0089](../02-decisions/adr/ADR-0089-return-window-and-inspection-deadline.md)에 있다. 선택은 출고별 168시간 경계 포함, 5영업일 초과 시 표시·gauge만, 자동 승인·환불 없음, 설정 flag로 기간 검사 rollback이다. 비용 0인 “do nothing”은 기준선 7/7 수락으로 재현된 무기한 요청을 남기므로 기각했다.

## Claims (claim | evidence | confidence)

| Claim | Evidence | Confidence |
| --- | --- | --- |
| 신규 반품의 기본 7일 이후 요청은 HTTP 409 `RETURN_WINDOW_EXPIRED`이고 행을 만들지 않는다 | 경계 HTTP 통합, 기준선 7/7 vs 변경 후 4/4 만료 거절·잔존 행 0 | local-only |
| 경계는 포함이며 기준 시각은 반품 줄의 출고 `delivered_at`이다 | PostgreSQL 경계·분할 테스트, M2/M4 | confirmed |
| 주문 행 잠금 뒤 Clock을 읽어 대기 중 만료된 요청이 거절된다 | PostgreSQL lock waiter 관측, 5/5 반복, M3 | local-only |
| 경계 동시 요청은 n=30 모두 수량 3을 지키고 +1초 요청은 모두 거절됐다 | 0/30 위반, 95% 상한 10%, M1 음성 통제 5/5가 위반 15건 검출 | local-only |
| 마감 계산은 월~금·Asia/Seoul이며 공휴일 미지원이다 | 표 기반 단위 테스트와 KST/UTC 경계, M7 | confirmed (구현 의미만) |
| 검수 초과는 상태·돈을 바꾸지 않고 목록/gauge로 표시하며 늦은 검수도 된다 | PostgreSQL 통합, gauge 2→1, late/on-time 환불 비교, M8/M9/M12 | local-only |
| 운영자가 실제로 경보를 받는다 | 저장소 tracked alert-rule·receiver 설정 0건; `observability/prometheus.yml:5-9` scrape, `ReturnInspectionMetrics.java:23` gauge, `application-observability.yml:17-18` endpoint 노출 | 외부 차단: 운영 Prometheus alert-rule와 Alertmanager 수신자·라우팅 설정 필요. V1은 gauge만 내보내며 로그·경보 수신은 하지 않음 |
| 7일·5영업일이 법 또는 Seller 정책에 맞다 | [전자상거래법 제17조 제1항 제1호](https://www.law.go.kr/lsLinkCommonInfo.do?chrClsCd=010202&lsJoLnkSeq=1022341869)는 서면 수령일부터 7일, 공급이 늦으면 공급일부터 7일을 규정; 이 조항만으로 endpoint의 시작 기준·적용 범위나 5영업일 Seller 검수를 검증할 수 없음 | 결정: 법률 적합성은 V1 범위 밖. Seller 계약·관할 근거 없이 준수 주장하지 않음. 설정 기본값 유지와 법무 승인 선행 중 설정 가능 기본값을 선택. 재검토 조건: 첫 Seller 계약 또는 관할 법무 검토 |
| 공휴일이 낀 5영업일 마감 | `ReturnPolicy.java:68-75,92-95`는 주말만 제외하며 주입 가능한 달력 없음. 월요일 2026-12-21 수령·5일·12-25 단일 휴일 가정 시 V1 배타 마감 12-29 00:00, 휴일도 세면 12-30 00:00 | 결정: V1은 주말만 제외하고 공휴일을 건너뛰지 않음; 이 예에서 1일 일찍 초과 표시. full calendar 구현 대신 이 규칙을 유지. 재검토 조건: 운영 국가 확정 또는 휴일 때문에 마감 분쟁 1건 |
| provider 실패 뒤 재검수에도 dueAt이 유지된다 | 통합 테스트 `reinspectionAfterProviderRefundFailureKeepsTheOriginalInspectionDeadline`: 첫 검수에서 provider가 `FAILED`를 돌려준 뒤 같은 검수를 다시 호출하면 수정 후 `REFUND_RECORDED`가 되고 `receivedAt`·`inspectionDueAt`은 그대로다(수정 전에는 재호출이 `OrderException`으로 막혀 환불이 기록되지 않았다) | local-only(실행함, 결정적 시험 1건, 수정 전·후 비교) |
| 설정값·DST 경계 | UTC·Asia/Seoul·America/New_York × P1H/P7D/P30D 18개 return-window 시나리오, 영업일 1/5/30 × 두 2026 NY DST 주변 구간 8,640개 수령 시각; 경계 ±1초 단위 확인 | local-only: 이 설정·시간대·날짜 행렬에서 전부 통과. 공휴일은 위 G2 결정이며 모든 미래 생산 날짜에 대한 주장은 아님 |
| 사전 등록 이후 추가: 잘못된 `business-zone` 문자열의 Spring binding이 시작을 막는다 | 이 설정-binding 경로용 app-context test를 실행하지 않음; 기존 테스트는 값 경계와 기본값 일치만 검증 | 결정(범위 밖): 시간 계산 invariant와 property conversion 경로는 별개이고 현 운영 zone 변경 요구도 없다. 잘못된 설정 app-context 시험 대안은 고려했으나 별도 Spring context를 추가하지 않음. 재검토 조건: 첫 운영자 지정 zone 기능 또는 invalid-zone 배포 차단 1건 |

## 최종 가설 판정

| 가설 | 판정 | 요약 |
| --- | --- | --- |
| H1 | confirmed | 기준선 7/7 수락, 비통제 지점 6/6 |
| H2 | confirmed | 변경 후 만료 4/4 거절, 행·환불 요청 0, HTTP 코드 고정 |
| H3 | confirmed | -1초·경계 수락, +1초 거절; M2 탐지 |
| H4 | confirmed | 분할 출고별 기준, 혼합 요청 원자적 거절; M4 탐지 |
| H5 | confirmed | 만료 뒤 같은 key replay는 원응답, 새 key는 거절; M5 탐지 |
| H6 | local-only | 잠금 대기 5/5, M3 탐지; 0/5 상한 60% |
| H7 | local-only | 경계 경합 n=30 위반 0, 상한 10%; M1 음성 통제 n=5 위반 15건 탐지 |
| H8 | confirmed | 주말 skip, 금요일→화요일, UTC·서울·뉴욕 DST 주변 행렬 M7 탐지 |
| H9 | confirmed | 마감 -1초/-1ns 정상, 정각부터 overdue; M8 탐지 |
| H10 | local-only | overdue flag·gauge·no-auto-money·late refund 확인; 3 RECEIVED 상태 유지, gauge 2→1 |
| H11 | local-only(실행함) | 수정 후 통과, 수정 전 실패(재검수가 `OrderException`으로 막힘). 마감 불변은 확인됐고, 시험이 환불 키 결함을 드러내 고쳤다(아래 후속 절) |
| H12 | local-only | ReturnPolicyTest 범위 값 검증 및 `application.yml` 기본값/code 기본값 일치 |
| H13 | confirmed | 실제 env flag=false에서 7/7, 기본 flag=true에서 만료 4/4 거절; 서비스 fixture off→on 시험 |

## 후속 점검(2026-11-26): provider 환불 실패 뒤 재검수가 막히던 결함

이 절은 H11이 `inconclusive`로 남았던 이유(provider 실패 뒤 재검수를 실행하지 않았다)를 실행으로 닫은 결과다. 본문과 구분하려고 따로 둔다.

- 문제: 반품 검수(`OrderReturnServiceImpl.inspect`)에서 환불을 송금하려는 첫 시도를 provider가 `FAILED`로 돌려주면, 같은 검수를 다시 호출해도 환불이 기록되지 않고 `OrderException`이 났다. 환불 요청 키를 고르는 `OrderRefundServiceImpl`이 재시도 횟수(`-r` 접미사 개수)가 0이면 기본 키를 다시 썼고, 그 키의 기록이 이미 터미널 `FAILED`라서 새 송금 작업이 만들어지지 않았다.
- 재현(결정적 시험 1건): `OrderReturnWindowIntegrationTest.reinspectionAfterProviderRefundFailureKeepsTheOriginalInspectionDeadline`. 수정 전에는 이 시험이 재검수 호출(318행)에서 `OrderException`으로 실패하고, 수정 후에는 `REFUND_RECORDED`가 되고 `receivedAt`과 `inspectionDueAt`이 첫 시도와 같다. 같은 시험을 수정을 제거한 채 다시 돌려 실패함을 확인했고(변이 1건) 복원은 `cmp`로 바이트 동일을 확인했다.
- 수정: 가장 최근 시도가 `FAILED`일 때만 다음 회차 키(`-rN+1`)를 만든다. `FAILED`가 아닌 시도(진행 중, 결과 불명, 응답 유실)는 같은 키를 다시 써서 같은 송금 작업을 이어 간다. 그래야 이중 환불이 생기지 않는다.
- 금전 안전: 새 키를 만드는 조건은 "최근 시도가 영구 실패로 확정됨" 하나뿐이다. 결과 불명 상태에서 새 키를 만들지 않는다.
- 한계: 시험은 결정적 1건이고 provider는 대역이다. 실제 PayPal Sandbox에서 환불 실패를 유도해 재검수까지 이어 본 실행은 없다. 결과 불명(`UNKNOWN`)에서 재호출이 같은 키를 쓰는지는 기존 시험이 다룬다고 가정하며 이 시험으로 확인하지 않았다.
- 되돌리기: 이 PR을 되돌리면 이전 키 선택으로 돌아간다(스키마 변경 없음).

## EXP-105 종결 결과

- **G1 — provider 실패 뒤 재검수:** 위 "후속 점검(2026-11-26)" 절이 실행으로 닫았다(#679, D-173). 이 PR은 그 시험을 다시 쓰지 않는다.
- **G2 — 공휴일:** `git grep -n -E "BusinessDay|BusinessCalendar|holiday|Holiday|isBusinessDay|calendar" -- atlas-order/src/main/java/com/atlas/order atlas-order/src/test/java/com/atlas/order` 결과 달력 주입 지점 0개; `ReturnPolicy.java`는 월~금만 센다. 다음 계산으로 방향을 확인했다. V1은 2026-12-21 월요일 수령·5일 때 12-25를 세어 12-29 00:00 KST에 마감하지만, 같은 날을 휴일로 건너뛰면 12-30 00:00 KST다. V1 표시가 하루 일찍 시작하는 concrete example이며 달력 미도입은 위의 결정으로 종결한다.

  ```bash
  python3 - <<'PY'
  from datetime import date, timedelta
  start, holiday = date(2026, 12, 21), date(2026, 12, 25)
  def due(skip_holiday):
      day, remaining = start, 5
      while remaining:
          day += timedelta(days=1)
          if day.weekday() < 5 and not (skip_holiday and day == holiday):
              remaining -= 1
      return day + timedelta(days=1)
  print(due(False), due(True))
  PY
  ```

- **G3 — 설정·DST 행렬:** `ReturnPolicyTest` 실행에서 UTC·Asia/Seoul·America/New_York × P1H/P7D/P30D 18개 window 시나리오와 영업일 1/5/30 × 두 뉴욕 DST 전환 구간의 수령 시각 8,640개를 확인했다. 각 deadline은 현지 자정, 직전 날짜의 평일 수 N개, overdue `-1초/정각/+1초` 경계를 만족했다. 아래 unit 명령에서 21 tests, 실패 0.
- **사전 등록 이후 추가 — invalid ZoneId 설정 subclaim:** Spring `business-zone` property conversion이 잘못된 문자열에서 시작을 막는지는 시험하지 않았다. 마감 함수의 시간대 invariant와 다른 입력 검증 경로이고 V1은 운영자가 zone을 바꾸는 기능을 별도로 요구하지 않아, 잘못된 값을 넣은 전체 app-context 시험 대안은 추가하지 않기로 결정했다. 재검토 조건: 운영자 지정 zone 기능을 추가하거나 invalid-zone 때문에 배포가 한 번 차단될 때.
- **G4 — alert 수신:** `git grep -n -i -E "rule_files|alerting:|alert:|groups:|receivers:|route:|Alertmanager|PrometheusRule" -- observability grafana`는 0줄이다. scrape 설정과 actuator gauge 위치는 claims 표에 적었다. 외부 운영 alert-rule/Alertmanager 수신·라우팅 설정 입력이 없어 실제 수신은 외부 차단; V1은 gauge만 노출한다.
- **G5 — 법·Seller 정책:** `git grep -n -i -E "반품 기간|5영업일|청약철회|전자상거래법" -- docs/01-requirements docs/02-decisions/adr docs/00-governance`는 13줄이며 기존 문서는 두 값을 가정·feature-config로만 기록한다. 법제처 [전자상거래법 제17조](https://www.law.go.kr/lsLinkCommonInfo.do?chrClsCd=010202&lsJoLnkSeq=1022341869) 제1항 제1호만 확인했다(서면 수령일부터 7일, 재화 공급이 늦으면 공급일부터 7일); 이는 이 endpoint의 시작 기준·전체 적용 범위·Seller의 5영업일 검수를 승인하지 않는다. 법률 적합성 주장은 범위 밖으로 결정하고 첫 Seller 계약 또는 관할 법무 검토 시 재검토한다.
- **재현·변이 명령:** 아래 두 명령은 컴파일·Checkstyle도 포함한다. 첫 명령은 PostgreSQL 통합 클래스 7 tests, 둘째는 단위 클래스 21 tests를 실행한다.

  ```bash
  ./gradlew --no-daemon --console=plain compileJava compileTestJava checkstyleMain checkstyleTest :atlas-application:test --tests 'com.atlas.order.OrderReturnWindowIntegrationTest'
  ./gradlew --no-daemon --console=plain compileJava compileTestJava checkstyleMain checkstyleTest :atlas-order:test --tests '*ReturnPolicyTest'
  ```

  단위 시험 쪽은 이 세션에서 다시 실행해 확인했다(2026-11-26): `ReturnPolicyTest` 21/21 통과, 변이 뒤 매번 저장본과 `cmp` 일치. 변이와 결과: 검수 초과 판정을 `now.isAfter(due)`로 바꿈(경계 정각 오판) 21건 중 2건 실패, 검수 마감 계산을 `plusDays(2)`로 바꿈 10건 실패, DST 구간에서 반품 기간을 현지 달력일로 계산(`ZonedDateTime.plusDays`) 4건 실패. 통합 시험 쪽(PostgreSQL 7건과 변이 "실패한 idempotency key 재사용 1/7, dueAt을 현재 시각으로 재계산 2/7, 검수 재시도 때 received_at 재기록 1/7")과 "UTC 자정으로 계산 10/21" 변이는 local-only(이전 에이전트 보고)이며 이 세션에서 다시 실행하지 않았다.
- **실제 롤백 드릴:** 세 Java 파일(`OrderRefundServiceImpl.java`, `OrderReturnWindowIntegrationTest.java`, `ReturnPolicyTest.java`)의 실제 diff를 scratch copy에 적용하고 `git apply --reverse actual-java.patch`로 되돌린 뒤 다시 적용했다. 순방향 적용·HEAD 복원·최종 재적용 각각 `cmp` 3/3 일치; 작업 worktree는 건드리지 않았다. 명령 형식: `git diff --binary HEAD -- <three files>`, `git apply actual-java.patch`, `git apply --reverse actual-java.patch`, 양방향 `cmp`.
- **혼란 요인·미실행 범위:** 통합 시험은 PostgreSQL 18.6과 로컬 provider status stub이며 실제 PayPal 계정·운영 receiver 설정을 사용하지 않았다. 시간은 조정 가능 Clock/고정 Instant라 호스트 시계 영향이 없고 DST 행렬은 순수 단위 시험이다. 도구: 일회성 `markdownlint-cli2@0.24.0` (`npx --yes`, Markdown lint); 별도 시스템 도구 설치는 없다. lint 명령 `npx --yes markdownlint-cli2@0.24.0 docs/03-experiments/EXP-093-return-window-and-inspection-deadline.md` 결과 0 issues. 전체 `clean check`는 이 작업에서 재실행하지 않았다(부모가 실행); 운영 데이터 조회·실제 Seller 계약 검토도 범위 밖/외부 입력 차단이다.
