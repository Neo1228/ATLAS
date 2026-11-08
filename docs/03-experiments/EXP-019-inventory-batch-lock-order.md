# EXP-019 — Inventory batch lock order 검증

**상태:** 검증 완료 · production 변경 없음 · local-only

## 시나리오

- Testcontainers PostgreSQL 18.6
- Seller 2명, Seller별 SKU 1개, pool capacity 각 1
- 서로 다른 Order 2개가 같은 두 Seller/SKU를 반대 입력 순서로 동시 예약
- Worker 2개 · `CyclicBarrier(2)` 시작 동기화 · barrier 10초 / Future 20초 제한
- [테스트 위치](../../ATLAS-application/src/test/java/com/atlas/inventory/InventoryConcurrencyIntegrationTest.java)

## 결과

| 검증 | 결과 |
|---|---|
| Focused `InventoryConcurrencyIntegrationTest` | 3 tests · 0 failure · 0 error · 0 skipped |
| Batch 결과 | 성공 1건 · `INSUFFICIENT_STOCK` 1건 |
| Reservation | 성공 Order 2건 · 실패 Order 0건 |
| 각 pool | `available=0`, `reserved=1`, `claimed=0` |
| Capacity invariant | `available + reserved + claimed = initialCapacity` 통과 |
| Deadlock/timeout | 미발생 |
| 전체 Gradle `check` + `bootJar` | 성공 · 40 suites · 202 tests · 0 failure/error/skip · 53 actionable tasks |

## 실행 명령

```sh
export JAVA_HOME="$(brew --prefix openjdk@25)/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
export DOCKER_HOST="$(docker context inspect --format '{{ .Endpoints.docker.Host }}')"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock

./gradlew --no-daemon :application-module:test \
  --tests com.atlas.inventory.InventoryConcurrencyIntegrationTest
./gradlew --no-daemon check :application-module:bootJar
```

- Runtime: Java 25 · Colima Docker context · PostgreSQL 18.6
- Test XML: 40 suites · 202 tests · 0 failure · 0 error · 0 skipped
- `bootJar`: 성공

## 한계

- Barrier는 요청 시작만 동기화. DB 내부 interleaving 보장 아님
- 한정된 Testcontainers 시나리오. Universal deadlock freedom, multi-process coordination, 운영 capacity 증명 아님
- `pg_locks` wait duration·RPS/TPS·latency 미측정
