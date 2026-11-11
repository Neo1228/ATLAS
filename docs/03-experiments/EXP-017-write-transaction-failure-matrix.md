# EXP-017 — 쓰기 transaction·동시성 검증

**검증 결과:** commit `f0dca97874079461aa5d4ebfe5e5b0289acbc4ad` 기준 · PostgreSQL Testcontainers 18.6

## 목적과 범위

- Cart checkout의 같은 Buyer·같은 idempotency key 동시 요청
- 기존 checkout rollback 및 Inventory capacity invariant 회귀 확인
- 실험 중 application code, migration, index, lock mode와 connection pool 설정은 변경하지 않음.

## 환경·데이터·workload

### 실행 시나리오
- Seller 1명, SKU 1개, initial available 2, 요청 수량 1
- Executor 2개 worker; `CyclicBarrier(2)`로 service 호출 동시 시작
- 같은 Buyer와 idempotency key로 `CartCheckoutService.checkout` 두 번 실행
- Barrier 대기 제한 10초, 각 Future 결과 제한 20초

## 결과

| Invariant | 결과 |
|---|---|
| 두 응답의 checkout ID / order ID 일치 | 통과 |
| 해당 Buyer의 `buyer_checkout` | 1건 |
| checkout의 `purchase_order` | 1건 |
| `RESERVED` inventory reservation | 1건 |
| Checkout 후 cart | 비어 있음 |
| Stock | available 1 · reserved 1 · claimed 0 |
| `available + reserved + claimed` 보존 | 통과 |
| 기존 late-line / cross-Seller rollback | 기존 테스트 유지 및 전체 회귀 통과 |

동일 key 재시도에서 checkout·order·reservation을 중복 생성하지 않고 응답과 inventory invariant를 보존. Lock-wait 시간은 측정하지 않음.

## 재현 방법

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 25)"
export PATH="$JAVA_HOME/bin:$PATH"
export DOCKER_HOST="$(docker context inspect --format '{{ .Endpoints.docker.Host }}')"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
./gradlew --no-daemon --rerun-tasks check :application-module:bootJar
```

### 검증 결과
- Gradle `check` + `bootJar`: 성공
- 40 test suites · 201 tests · 0 failure · 0 error · 0 skipped
- Testcontainers PostgreSQL 18.6

## 해석과 한계

- 테스트는 같은 JVM의 두 worker를 사용. multi-process/instance 조정 증거가 아님.
- `pg_locks` / `pg_stat_activity` wait snapshot, lock wait duration, deadlock rate, throughput 미측정.
- Cart checkout의 PostgreSQL transaction, idempotency와 Inventory invariant 검증. Campaign expiry race와 refund-failure recovery는 본 실험의 변경 경로가 아님.
- 해당 run의 전체 회귀 결과: 40 suites · 201 tests · 실패 0 · 오류 0 · skipped 0. 개별 시나리오의 metric을 새로 측정한 결과는 아님.
- 이 시나리오는 deadlock rate나 throughput을 측정하지 않음. 추가 튜닝 전에는 별도 scenario와 before/after measurement 필요.
