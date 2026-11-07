# EXP-017 — 쓰기 transaction·동시성 검증

**상태:** local-only · 기준 commit `f0dca97874079461aa5d4ebfe5e5b0289acbc4ad` · PostgreSQL Testcontainers 18.6

## 범위

- Cart checkout의 같은 Buyer·같은 idempotency key 동시 요청
- 기존 checkout rollback 및 Inventory capacity invariant 회귀 확인
- production code, migration, index, lock mode, pool 설정 변경 없음

## 시나리오

- Seller 1명, SKU 1개, initial available 2, 요청 수량 1
- Executor 2개 worker; `CyclicBarrier(2)`로 service 호출 동시 시작
- 같은 Buyer와 idempotency key로 `CartCheckoutService.checkout` 두 번 실행
- Barrier 대기 제한 10초, 각 Future 결과 제한 20초

## 검증 결과

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

동일 key 재시도에서 중복 checkout·order·reservation이 생기지 않고, 응답과 재고 invariant가 일치함. 실패 유도나 실제 lock-wait timing을 측정한 결과는 아님.

## 실행 검증

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 25)"
export PATH="$JAVA_HOME/bin:$PATH"
export DOCKER_HOST="$(docker context inspect --format '{{ .Endpoints.docker.Host }}')"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
./gradlew --no-daemon --rerun-tasks check :application-module:bootJar
```

- Gradle `check` + `bootJar`: 성공
- 40 test suites · 201 tests · 0 failure · 0 error · 0 skipped
- Testcontainers PostgreSQL 18.6

## 해석과 미검증 경계

- 테스트는 같은 JVM의 두 worker를 사용. multi-process/instance 조정 증거가 아님.
- `pg_locks` / `pg_stat_activity` wait snapshot, lock wait duration, deadlock rate, throughput 미측정.
- Cart transaction의 PostgreSQL 직렬화·멱등성 불변조건을 검증. PSP `REQUIRES_NEW`, Campaign expiry race, refund failure path는 이번 변경에서 수정하지 않음.
- 201개 전체 테스트가 기존 rollback·Inventory concurrency·PSP durability·refund recovery 회귀를 통과했으나, 각 시나리오의 개별 metric을 새로 수집한 것은 아님.
- 성능·사용자 수·용량·SLO 주장은 하지 않음. 다음 튜닝은 별도 scenario와 before/after 근거가 필요.
