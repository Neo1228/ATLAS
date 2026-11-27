# ADR-0107: 다중 인스턴스 전제의 공유 상태는 DB 한 문장·행 잠금 아래 재조회·lease 펜스 결과로 지키고, 만료 sweep 잠금은 transaction 범위로 둔다

- 상태: Accepted(사용자 전제 "N대가 LB 뒤에서 rolling 배포·종료·DB 장애 조치를 겪는다"에 따른 에이전트 결정. 사용자 승인 여부는 확인하지 못함)
- 날짜: 2026-11-28
- 관련 결정: [ADR-0048](ADR-0048-authentication-attempt-throttling.md)(인증 시도 제한), [ADR-0053](ADR-0053-shared-infra-adoption-gates.md)(공유 인프라 도입 조건), [distributed-readiness](../distributed-readiness.md), [sizing-envelope](../sizing-envelope.md) 3.4
- 근거: [EXP-118](../../03-experiments/EXP-118-multi-instance-assumption-audit.md), EXP-117 G1 재현(EXP-118 "G1" 절), 이슈 [#730](https://github.com/Neo1228/ATLAS/issues/730)

## 문제

app이 한 대라는 가정이 JVM 잠금·메모리 상태·스케줄 작업·시계·배포 순서에 흩어져 있었다. EXP-118이 실제 bootJar 인스턴스 2~3대와 PostgreSQL 하나로 세고 시험으로 재현한 결과는 이렇다. 인증 주체 한도는 "읽고 판단한 뒤 검증 후 기록"이라 동시 시도가 한도를 넘었다(한 주체에 오답 90건 동시, 한도 10에 인스턴스 1대 40~48건, 3대 89~90건이 검증까지 감). location claim은 lease 펜스가 0행을 돌려줘도 CLAIMED를 답했다(EXP-117 G1, 순서를 고정한 시험으로 재현). 결제 결과 반영은 잠금 밖에서 읽은 스냅숏을 썼고, 순서를 고정한 시험에서는 그 스냅숏이 `PAID`를 되돌렸지만 현행 jar 2대에서 두 요청을 다른 인스턴스로 보낸 200회(모드마다 100)에서는 한 번도 나지 않았다(H2a 반증). 만료 sweep 잠금은 session advisory lock이라 sizing-envelope가 T2에서 권장한 transaction mode pooler 뒤에서 동작하지 않았다(30초 3회에 겹침 15~24, 끝난 뒤 서버 연결에 남은 lock 1, 최장 공백 최대 6.5초).

## 결정

1. **인증 주체 한도는 검증 전에 한 문장으로 예약한다.** `reserveSubjectAttempt`가 조건부 upsert(`failures < limit`) 한 번으로 비교와 증가를 함께 하고, 출처 한도에 막힌 시도는 `releaseSubjectAttempt`로 돌려준다. 성공은 ADR-0048 결정 6대로 카운터를 지운다. 열 이름 `failures`는 "창 안에서 검증까지 간 시도 수"로 뜻만 바뀌고 이름은 그대로라 old·new jar가 섞여도 한 시도를 두 번 세지 않는다.
   - 성공 경로: 올바른 비밀번호도 검증이 끝날 때까지 한 칸을 쓴다. 한 주체에 동시 시도가 한도를 넘으면 넘는 몫은 비밀번호가 맞아도 검증 전에 거절된다(EXP-118 H1d: 올바른 로그인 15건 동시면 회차마다 5건). 이 거절은 받아들인다(H1f 대안 a). 한도만큼의 시도가 동시에 검증 중일 때만 생기고, 그 시도가 성공하면 카운터가 지워져 거절된 쪽의 재시도는 통과한다(250 ms 뒤 재시도에서 5건 모두 성공). 한도 안의 동시 로그인은 서로 막지 않고 성공 뒤 카운터는 0이다.
   - 검증 중 종료: 예약만 남는다(유령 실패, 검증 중 `kill -9` 3회에서 보낸 5건이 모두 유령 5로 남음). 수는 그 순간 그 인스턴스에서 검증 중이던 시도 수 이하이고 다음 성공 로그인이나 창 끝(기본 15분)에 사라진다. ADR-0048의 목적(추측 방어, 성공하면 풀림, 시간이 지나면 풀림)을 해치지 않으므로 예약에 따로 만료를 두지 않는다.
2. **결제 결과는 checkout(주문) 행 잠금 아래에서 다시 읽는다.** `CheckoutPaymentServiceImpl.applyPaymentResult`와 `OrderServiceImpl.applyPayment`는 호출자가 들고 온 스냅숏 대신 잠금을 잡은 뒤 읽은 결제 상태로 checkout과 주문 상태를 정한다. JVM 안의 `OwnerLockRegistry`는 효율(같은 JVM의 중복 PayPal 호출 감소)로만 남는다.
3. **lease를 잃은 claim은 기록된 상태로 답한다(EXP-117 G1).** `finish()`가 lease token으로 막힌 기록의 결과를 돌려주고, `claim()`은 CLAIMED를 기록했을 때만 CLAIMED를 답한다. 기록하지 못했으면 workflow를 다시 읽어 취소·종결은 REJECTED(호출자가 승인을 void), 그 밖은 PENDING으로 답한다. 기존 lease token이 펜스이므로 새 열이나 토큰을 더하지 않는다.
4. **만료 sweep 잠금은 transaction 범위 advisory lock이다.** 풀에서 따로 받은 연결에 transaction을 열고 `pg_try_advisory_xact_lock`을 잡은 채 sweep을 돌린 뒤 rollback으로 푼다. transaction pooling에서도 잡기부터 풀기까지 같은 서버 연결에 머문다(PgBouncer `pool_mode=transaction` 뒤 30초 3회에 겹침 0, 남은 lock 0, 오류 0. 같은 조건에서 session lock은 겹침 15·19·24, 남은 lock 1). 운영 코드의 session 범위 advisory lock은 0곳이 됐다.
5. **V13은 만들지 않는다.** H2a(다른 인스턴스의 오래된 스냅숏이 `PAID`를 되돌림)가 현행 jar 2대의 200회(모드마다 100)에서 0회라, 사전 등록한 H3b 규칙대로 DB 가드를 만들지 않고 R-mix 칸도 돌리지 않았다. 결정 2의 재조회는 순서를 고정한 시험 2개가 수정 전 실패·수정 뒤 통과하므로 남긴다(스키마 변경 없음, 이미 잡는 행 잠금 아래 읽기 1회).
6. **배포 제약은 코드 옆과 운영 가이드에 둔다.** JWT 서명 비밀은 모든 인스턴스가 같다(임시 키는 단일 인스턴스 전용. 임시 키로 2대를 띄우면 인증 GET 20건 중 7·7·14건이 401). 노드 시계는 NTP로 맞춘다. 인스턴스별 한도(bulkhead, 캐시)는 클러스터에서 N배다. 게이지는 `max`로 본다. transaction mode pooler를 넣을 때는 시간 제한을 URL `options`에서 DB 역할 설정으로 옮기고 `max_prepared_statements`를 0보다 크게 둔다(sizing-envelope 3.4).

## 대안과 기각 이유

| 안 | 근거 | 결정 |
|---|---|---|
| 인증: 현행(검증 뒤 기록) | 한도 10에 1대 40~48건, 3대 89~90건이 검증까지 감(H1a·H1b) | ✗ 기각 |
| 인증: Redis 원자 카운터 | ADR-0053 조건(DB가 병목이라는 측정) 없음. DB 한 문장으로 같은 원자성을 얻음 | ✗ 기각 |
| 인증: 예약에 만료를 둠(예약 행 또는 예약 시각 열) | 유령은 검증 중 시도 수 이하이고 성공·창 끝에 사라짐(H1e: 유령 5는 보낸 수 이하, 성공 뒤 0, 창 끝 뒤 오답 1회에 1). 예약 행은 ADR-0048이 기각한 sliding log 비용 | ✗ 기각 |
| 인증: 진행 중 예약에 밀려 거절된 시도를 서버가 200 ms 뒤 한 번 다시 예약(H1f 대안 b) | 올바른 로그인 15건을 모두 받았지만, 오답 90건 묶음 동안 같은 인스턴스의 탐침 p99 중앙값이 93 → 308 ms로 늘고 묶음이 2.3배 오래 걸림. 거절된 오답도 모두 200 ms씩 Tomcat 스레드를 쥠. 공격 중에는 오답 예약이 남아 재예약도 거절되므로 살리는 경우는 올바른 로그인끼리 몰린 때뿐 | ✗ 기각 |
| 인증: 성공이 자기 예약만 돌려줌(카운터를 지우지 않음) | ADR-0048 결정 6(성공하면 풀림)을 바꿈. 정상 사용자가 앞선 오답을 창 끝까지 안고 감 | ✗ 기각 |
| 결제: DB 트리거로 `PAID`를 되돌리지 못하게 함(V13) | H2a가 현행 jar 2대 200회에서 0회라 사전 규칙(H3b)상 만들지 않음. 트리거가 막을 역전이 측정에서 나오지 않았고, 재조회가 시험으로 재현한 경로를 이미 막음 | ✗ 기각 |
| 결제: 분산 잠금(Redis 등) | ADR-0053 조건 없음. 행 잠금 아래 재조회가 순서 고정 시험의 역전을 막고, 현행 jar 2대 200회와 수정 jar 2대 200회 모두 역전 0 | ✗ 기각 |
| G1: 새 fencing 토큰·location 쪽 토큰 검사 | 기존 lease token이 이미 order DB 쓰기를 막음. 결과를 무시하던 코드만 고치면 됨 | ✗ 기각 |
| sweep: session lock + "transaction pooling 금지" 제약 | T2 pooler 권장과 충돌. 측정상 transaction 범위 lock이 같은 상호 배제(직접 연결 실행 수 99.8~102.1%, 겹침 0) | ✗ 기각 |
| sweep: lease 행(새 표, migration) | 시간 기반이라 엄밀한 배제가 아니고 표·migration이 늘어남. transaction 범위 lock으로 충분 | ✗ 기각 |
| 위 1~4 채택 | EXP-118 | ✓ 채택 |

## 결과

- 인증 주체 한도는 인스턴스 수와 무관하게 창마다 검증까지 가는 시도를 한도 이하로 막는다. 대가는 한도를 넘는 동시 시도(올바른 비밀번호 포함)의 일시 거절과 검증 중 종료의 유령 예약이다.
- 결제 결과 반영은 인스턴스 수와 무관하게 앞으로만 간다. 같은 JVM 안에서는 여전히 `OwnerLockRegistry`가 중복 PayPal 호출을 줄인다. 다른 JVM 사이의 중복 호출은 `PayPal-Request-Id` 멱등이 흡수한다(EXP-118 P-N2: 요청 id 100개에 capture 호출 110~126건, 최종 상태는 모두 `PAID`·`CAPTURED`).
- claim 결과가 lease 펜스와 어긋나지 않으므로 "매입은 됐는데 재고는 풀림"이 이 경로로는 생기지 않는다.
- 만료 sweep의 락 연결은 sweep 동안(시간 예산 2초 + 행 잠금 대기 5초 이내) idle in transaction으로 보인다. xid를 받지 않아 마감 worker의 스냅숏 경계를 붙잡지 않는다(S칸 idle in transaction 표본 150~156개 중 xid 보유 0).

## 롤백

- 네 변경 모두 스키마를 바꾸지 않는다. 이전 jar로 다시 배포하면 된다. 되돌린 동안에는 위 "문제"의 세 결함이 다시 생기고, transaction mode pooler 뒤에서는 만료 sweep이 겹치거나 멈춘다.
- rolling 배포 중 old·new jar가 섞여도 `auth_subject_bucket.failures`는 한 시도를 한 번만 센다(old jar는 실패 뒤, new jar는 검증 전에 1을 더함).

## 재검토 조건

- 인증 거절 중 "한도를 넘는 동시 올바른 로그인" 민원이 생기거나 한 주체의 동시 로그인이 한도(10)에 닿는 클라이언트가 생기면: 성공 예약을 따로 세는 안을 다시 잰다.
- 인스턴스 비정상 종료(OOM·`kill -9`)가 하루 1회를 넘거나 `subject-limit`을 3 이하로 줄이면: 유령 예약이 한도의 큰 몫이 되므로 예약 만료를 다시 잰다.
- 결제 상태가 뒤로 가는 전이가 생기면(예: `PAID`에서 결제 재시도): "결제 상태는 앞으로만 간다"는 재조회의 전제가 깨지므로 V13 트리거안을 다시 잰다.
- pooler를 넣거나 T2(앱 6대)에 닿으면: sizing-envelope 3.4 전제 세 가지를 그 pooler 버전에서 다시 확인한다.
- session 범위 advisory lock(`pg_advisory_lock`, `pg_try_advisory_lock`)이 운영 코드에 다시 생기면: transaction pooling 전제가 깨진다.
