# EXP-043: PostgreSQL 연결이 transaction 도중 끊겨도 원장이 남는가

**추적 이슈:** [#434](https://github.com/Neo1228/ATLAS/issues/434)

**상태:** 실측 완료 · 로컬, PostgreSQL 18.6 컨테이너

## 왜 이 실험을 했는가

기존 crash 테스트는 두 종류뿐이었다. 하나는 Mockito spy 로 예외를 주입하는 것이고(`PaymentWorkflowCrashWindowIntegrationTest`), 다른 하나는 PayPal 응답 유실이다. **어느 것도 "commit 전에 DB 연결이 사라지면 디스크에 무엇이 남는가"를 보여주지 않는다.**

이 질문이 중요한 이유는 원장이 두 테이블로 나뉘어 있기 때문이다. `settlement_journal`(분개 헤더)과 `settlement_entry`(차변·대변 줄)가 따로 INSERT 된다. 연결이 그 사이에 끊기면 **journal 만 남고 entry 가 없는 "고아 분개"** 가 생길 수 있고, 그러면 statement 가 차변 합 ≠ 대변 합인 분개를 읽는다.

기존 문서(`docs/02-decisions/distributed-readiness.md`)와 EXP-017/042 는 동시성과 PayPal 장애는 다뤘지만, **DB 연결 손실**은 다루지 않았다.

## 무엇을 측정했는가

Testcontainers PostgreSQL 18.6 에서, 진행 중인 transaction 의 백엔드 PID 를 **별도 커넥션**에서 `pg_terminate_backend(pid)` 로 종료했다. 무작위 타이밍이 아니라, INSERT 를 실행한 직후 같은 transaction 안에서 종료를 요청하는 방식으로 **경계를 지정**했다.

테스트: `ATLAS-application/src/test/java/com/atlas/settlement/SettlementDbFailureAtomicityIntegrationTest.java`

## 결과

| 시나리오 | 기대 | 실제 |
|---|---|---|
| transaction 중 백엔드 종료 (journal INSERT 후) | 아무 행도 남지 않음 | journal 0, entry 0, 고아 0 |
| 연결 손실 후 같은 attempt 로 재시도 | 정확히 1건 | journal 1건 |
| 지급 확정 중 백엔드 종료 | 상태 전이도 분개도 rollback | state `SENT` 유지, entry 수 불변 |
| 위 실패 후 확정 재시도 | 1회만 분개 | state `CONFIRMED`, entry +2 |
| 확정 반복 호출(3회) | 지급 1건 = journal 1건 | journal 1건, 차변 합 = 대변 합 |

**4 tests / 0 failures.**

## 무엇을 증명했고, 무엇을 증명하지 못했는가

### 증명한 것

- PostgreSQL 은 연결이 끊긴 uncommitted transaction 을 rollback 한다. 따라서 **journal 과 entry 는 함께 있거나 함께 없다.**
- 지급 상태 전이와 확정 분개가 같은 transaction 이면, 전이만 commit 되고 분개가 없는 상태가 만들어지지 않는다. 이것이 "지급은 CONFIRMED 인데 잔액은 그대로"라는 이중 지급 경로를 막는다.
- 연결 손실 후 재시도는 unique 제약으로 중복 분개를 만들지 않는다.

### 증명하지 못한 것 (정직하게)

**COMMIT 명령이 서버에 도달한 뒤 클라이언트가 응답을 못 받은 경우는 이 테스트로 덮이지 않는다.** 그 경우 서버는 이미 commit 했으므로 rollback 되지 않는다. 애플리케이션은 실패로 보지만 데이터는 남는다.

이 경계는 세 가지 이유로 **구조적으로 증명 불가**하다:
1. 클라이언트는 서버가 commit 했는지 알 수 없다(2단계 커밋 없이는 원리적으로 불가).
2. 이 경우의 안전은 rollback 이 아니라 **재시도 멱등성**이 담당한다.
3. 이 테스트는 그 멱등성의 절반(unique 제약 + insertIfAbsent 가 중복을 막는 것)을 검증한다. 나머지 절반(provider 측 중복 청구 방지)은 고정 request-id 로 이미 다뤄진다.

즉 이 테스트는 **"고아 분개가 생기지 않는다"는 원자성**을 증명하고, **"commit 성공 후 응답 유실"의 정확한 복구**는 증명하지 않는다. 후자는 at-least-once 를 전제로 한 멱등 재시도 설계로 흡수한다.

## 부수적으로 확인한 것

`settlement_entry.seller_id` 는 V52 에서 추가된 컬럼이다. 이 컬럼이 비어 있으면 `sellerBalance` 가 그 entry 를 어떤 seller 에게도 귀속시키지 않아 **지급 가능 잔액이 0으로 보인다.** 테스트 fixture 를 만들다 이 사실을 확인했고, 실제 코드(`insertEntries`)는 항상 sellerId 를 넘기므로 운영 경로에는 영향이 없다.

## 한계

- 로컬 Testcontainers 단일 노드다. 네트워크 파티션·복제 지연·failover 는 다루지 않는다.
- `pg_terminate_backend` 는 서버 측 종료다. 클라이언트 소켓만 끊기는 경우(`reset_peer`)는 Toxiproxy 가 필요하며, 이 실험에서는 다루지 않았다.
- 이 실험이 Toxiproxy 도입을 정당화하지는 않는다. `pg_terminate_backend` 로 같은 질문에 답할 수 있음을 확인했으므로, **별도 프록시 인프라 없이 결정적 테스트가 가능하다.**
