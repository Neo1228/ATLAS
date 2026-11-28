# ATLAS 산정 기준 (Sizing Envelope)

이 문서는 외부 사례를 **그대로 목표로 삼지 않고**, ATLAS가 실제로 감당해야 할 부하를 자체 기준으로 정의합니다. 외부 수치는 "우리가 어디에 있는지"를 가늠하는 참조점일 뿐이며, ATLAS의 목표치는 아래 Tier에서 따릅니다.

## 1. 왜 자체 기준이 필요한가

알리바바 Double 11 2020은 피크 **583,000 transactions/s**를 기록했습니다. 이 수치는 초당 주문 58만 건이며, 단일 PostgreSQL로는 불가능합니다(샤딩·전용 캐시·전용 큐·전용 검색 계층이 전제). ATLAS는 modular monolith 하나와 PostgreSQL 하나로 시작했으므로, 같은 숫자를 목표로 잡으면 설계가 아니라 모방이 됩니다.

반대로 "로컬에서 130 RPS 나왔다"를 용량으로 부르는 것도 과소평가입니다. 그 130 RPS는 2 vCPU 컨테이너에서 **이미 PostgreSQL이 2코어를 다 쓰던** 상태였습니다(EXP-035).

따라서 ATLAS는 **부하 등급(Tier)** 을 먼저 정하고, 각 등급에서 요구되는 자원을 계산합니다.

## 2. 부하 등급

| Tier | 의미 | 피크 주문 TPS | 피크 읽기 RPS | 전제 인프라 |
| --- | --- | ---: | ---: | --- |
| **T0 로컬 검증** | 개발·회귀 확인 | < 1 | < 50 | 단일 노드, 2 vCPU |
| **T1 초기 상용** | 소규모 셀러 마켓 | 5 | 500 | 앱 2대 + PG 1대(4 vCPU) + pooler |
| **T2 성장** | 카테고리 확장, 프로모션 | 50 | 5,000 | 앱 4–6대 + PG 8 vCPU + read replica + Redis |
| **T3 피크 이벤트** | 타임세일, 한정 판매 | 300 | 30,000 | 앱 오토스케일 + PG 16 vCPU + replica 2 + Redis cluster |
| **T4 참조 상한** | 업계 최상위(모방 대상 아님) | 583,000 | 수백만 | 샤딩·전용 계층 |

T4는 알리바바 수치를 **경계 표시**로만 둡니다. ATLAS의 로드맵 목표는 **T2 → T3**입니다.

## 3. 산정식 (ATLAS 파라미터)

### 3.1 트래픽

```text
읽기 RPS_peak  = DAU × 세션/일 × 요청/세션 ÷ 86,400 × 피크계수
주문 TPS_peak  = DAU × 주문/일 ÷ 86,400 × 피크계수
```

ATLAS 가정(명시적, 측정 아님):

| 파라미터 | 값 | 근거 |
| --- | --- | --- |
| 세션/일 | 2 | 재방문 탐색 패턴 가정 |
| 요청/세션 | 40 | 목록 6 + 상세·장바구니·주문 흐름 |
| 피크계수 | 5 | 업무 시간 집중 가정. 이벤트 오픈은 별도 |
| 주문/DAU/일 | 0.02 | 50명 중 1명 구매 가정 |

### 3.2 동시성 (Little's Law)

```text
동시 요청 C = RPS_peak × 지연 p99
```

EXP-035 실측을 대입합니다. 읽기 경로 p99는 부하에 따라 크게 달라지므로 **등급별로 다른 값**을 씁니다.

| Tier | RPS_peak | p99 (측정/가정) | 필요 동시성 |
| --- | ---: | ---: | ---: |
| T1 | 500 | 0.15 s | 75 |
| T2 | 5,000 | 0.12 s | 600 |
| T3 | 30,000 | 0.10 s | 3,000 |

### 3.3 코어 산정

```text
필요 코어 = RPS_peak ÷ (TPC × 목표사용률)
```

`TPC`(코어당 처리량)는 **ATLAS 실측**에서만 가져옵니다. EXP-035에서 2 vCPU 앱이 10 it/s(=60 RPS)를 0.1% 드롭으로 처리했고, 그때 앱 CPU는 평균 26%였습니다. 이를 보수적으로 쓰면:

```text
TPC_ATLAS ≥ 60 ÷ (2 × 0.26) ≈ 115 RPS/core   (하한, 캐시 없는 상태)
```

이 값은 **DB가 병목이 아닐 때**의 애플리케이션 처리량입니다. 실제 T2/T3에서는 DB가 먼저 포화되므로, 앱 코어보다 DB 용량이 결정 변수입니다.

### 3.4 DB 연결 예산

```text
필요 연결 = 앱 인스턴스 수 × Hikari max
DB 상한    = max_connections × 0.8   (운영·백업·모니터링 여유)
pooler 사용 시: 앱 연결 ≠ DB 연결
```

AWS RDS `db.r7g.large`(2 vCPU/16 GiB)의 기본 `max_connections`는 메모리 기반으로 약 **400** 수준입니다. 그러나 vCPU당 동시 실행 가능한 작업은 훨씬 적습니다. PgBouncer 실측(jadhav.dev)은 **128 연결**로 210K queries/s까지 도달했지만 그때 PostgreSQL CPU가 97%였습니다. 즉 **연결 수를 늘리는 것이 처리량을 늘리지 않습니다.**

ATLAS 기준:

| Tier | 앱 인스턴스 | Hikari/인스턴스 | 필요 DB 연결 | pooler |
| --- | ---: | ---: | ---: | --- |
| T1 | 2 | 10 | 20 | 불필요 |
| T2 | 6 | 10 | 60 | 권장 (transaction mode, 아래 전제 3개) |
| T3 | 오토스케일 12 | 10 | 120 | 필수 |

T3에서 pooler 없이 120 연결을 유지하면 PostgreSQL은 연결 관리에 CPU를 쓰고, 실제 쿼리 처리량은 늘지 않습니다. PgBouncer를 두면 DB 쪽 실제 연결을 **32–48**로 고정할 수 있습니다.

transaction mode 전제([EXP-118](../03-experiments/EXP-118-multi-instance-assumption-audit.md) H5, [ADR-0107](adr/ADR-0107-multi-instance-shared-state-guards.md)). transaction pooling은 서버 연결을 transaction 동안만 고정합니다. ATLAS가 연결에 기대는 세 가지를 PgBouncer 1.24.1(`pool_mode=transaction`) 뒤에서 확인했습니다.

1. **session advisory lock.** 운영 코드의 session 범위 advisory lock은 만료 sweep 잠금 1곳뿐이었고, 락 연결의 transaction 안에서 잡는 transaction 범위 lock으로 바꿨습니다. session lock은 PgBouncer 뒤에서 상호 배제를 잃거나 sweep이 멈추고 서버 연결에 lock이 남았습니다.
2. **시작 매개변수.** 기본 `ATLAS_DB_URL`은 `options=-c statement_timeout=...`로 시간 제한 세 개를 겁니다. PgBouncer는 이 연결을 `unsupported startup parameter in options: statement_timeout`으로 거절합니다. `track_extra_parameters`에 세 이름을 넣으면 연결은 되지만 세 값 모두 `SHOW`가 0이었습니다(적용되지 않음). DB 역할에 둔 값(`ALTER ROLE atlas IN DATABASE atlas SET statement_timeout = '30s'` 등)은 `options` 없는 연결에서 PgBouncer 뒤에서도 30s·5s·1min으로 적용됐습니다. pooler를 넣을 때는 URL에서 `options`를 빼고 역할 설정으로 옮긴 뒤 `SHOW`로 확인합니다. `ignore_startup_parameters=options`는 PgBouncer 문서대로 `options` 안의 모르는 매개변수를 무시하므로 역시 시간 제한이 빠집니다(실행해 보지 않음).
3. **prepared statement.** PgJDBC는 같은 문장의 5회째부터 이름 있는 서버 prepared statement를 씁니다. `max_prepared_statements=0`이면 `prepared statement "S_1" does not exist`(SQLSTATE 26000)로 실패했고, 200(문서 기본값)이면 오류가 없었습니다. 0으로 두는 PgBouncer라면 JDBC `prepareThreshold=0`을 씁니다.

### 3.5 캐시 계층

캐시는 **읽기 RPS × DB 비중**으로 판단합니다.

```text
캐시 필요 조건 = (읽기 RPS_peak × DB 조회 비중 × 평균 DB 시간) > DB 코어 여유
```

EXP-035 실측에서 100k 상품 기준 읽기 1건이 PostgreSQL CPU를 약 **0.33 ms** 사용했습니다(단일 노드 20 it/s=120 RPS에서 PG 2코어 100% 근사). T2의 5,000 RPS를 캐시 없이 처리하면 **1,650 ms CPU/초 ≈ 1.65 코어**가 목록 조회에만 필요합니다. 여기에 상세·주문이 붙으면 8 vCPU로는 부족합니다.

ATLAS 기준:

| Tier | 캐시 | 근거 |
| --- | --- | --- |
| T1 | 없음 | DB 여유 충분 |
| T2 | 목록 첫 페이지 + 상세 (TTL 짧게) | 위 계산상 DB 코어 부족 |
| T3 | 위 + 카테고리·가격 필터 결과 | 읽기 폭증 |

**중요:** 캐시는 정합성 계약이 필요합니다. ATLAS는 재고·주문·결제를 절대 캐시하지 않습니다. 캐시 대상은 **상품 목록/상세의 표시용 데이터**로 한정하고, TTL과 무효화 규칙을 문서화합니다.

## 4. 외부 사례와의 대응

| 외부 수치 | 출처 | ATLAS에서의 의미 |
| --- | --- | --- |
| 583,000 TPS (Double 11 2020) | [Alibaba Cloud](https://www.alibabacloud.com/customers/double-11) | T4 경계. 목표 아님 |
| 45K→210K q/s, 128 연결, PG CPU 22→97% | [jadhav.dev](https://jadhav.dev/blog/pgbouncer-multicore-limit/pgbouncer-from-45k-to-210k-queries-per-second) | pooler로 연결을 늘려도 결국 PG CPU가 한계 |
| 연결은 CPU보다 먼저 고갈 | [AWS Database Blog](https://aws.amazon.com/blogs/database/understanding-the-differences-between-pgbouncer-and-amazon-rds-proxy-for-postgresql-connection-pooling/) | T2 이상에서 pooler를 기본 구성으로 |
| 피크 CPU 50% 목표 | [Google SRE Workbook](https://sre.google/workbook/data-processing/) | ATLAS T2/T3의 목표 사용률 상한 |
| in-flight 동시성 기반 제한 | [Uber](https://www.uber.com/blog/from-static-rate-limiting-to-intelligent-load-management/) | RPS가 아니라 동시성으로 포화 판단 |

## 5. 트래픽 구성 (read-heavy but write-critical)

부하 시나리오는 읽기만 반복하면 안 됩니다. 실제 이커머스 세션은 읽기가 대부분이지만 **비용과 실패 모드는 소수 쓰기에서 나옵니다**.

공개 벤치마크 기준:

| 비율 | 값 | 출처 |
| --- | ---: | --- |
| 장바구니 추가 | 세션의 **6.04%** | Dynamic Yield rolling 12-month benchmark (2025-10~2026-09), 2차 요약 |
| 체크아웃 완료 | 세션의 약 1.5–2% | 동일 계열 벤치마크 |

따라서 ATLAS 여정 시나리오는 **세션 100건 중 6건 장바구니 추가, 그중 1–2건 체크아웃**으로 구성합니다. 1:1로 쓰기를 섞는 시나리오는 실제보다 쓰기를 과대평가합니다.

### 5.1 resume 동작

재방문 사용자는 이전 장바구니를 이어받습니다. 시나리오는 쓰기 전에 **기존 장바구니를 먼저 읽고**, 쓴 뒤 **즉시 다시 읽어 반영 여부를 검증**합니다. 이 read-after-write 검증이 캐시·read replica 도입 시 가장 먼저 깨지는 지점이므로 명시적으로 측정합니다.

### 5.2 핫키 편중

균등 분포 부하는 실제 프로모션을 반영하지 못합니다. 시나리오는 `HOT_SELLER_SHARE`로 일정 비율의 세션을 한 셀러에 고정해 락 경쟁과 핫 파티션을 드러냅니다.

## 6. 인프라 선택의 근거

| 선택 | 근거 수치 | ATLAS 적용 |
| --- | --- | --- |
| 캐시 도입 | Facebook NSDI'13: 장애 시 hit rate가 4분 내 35%, 최대 50%까지 회복; 서버 1대 손실을 99% 완화 | 캐시는 **실패 시 origin 보호**까지 설계해야 함. hit ratio만 보면 안 됨 |
| 캐시 워밍 | 동일 논문: 90% hit rate 도달에 수 시간 | 배포·재시작 시 origin 폭주 전제 |
| read replica | Aurora SIGMOD'17: 1,000→10,000 writes/s에서 lag 2.62→5.38 ms. MySQL은 1초→300초 | replica는 **낙관적 하한**이며, ATLAS는 read-after-write 경로를 replica로 보내면 안 됨 |
| replica offload | GitHub freno: replica로 30% 라우팅, 지연 작업은 lag 기반 대기(<600ms p95) | ATLAS read offload 상한은 30% 수준으로 보수적으로 |
| pooler | PgBouncer 45K→210K q/s, 128 연결 고정, PG CPU 22→97% | 연결 증설이 아니라 **다중화**가 해법 |
| pooler 주의 | transaction mode는 세션 상태를 보존하지 않음 | 확인함(EXP-118 H5): session advisory lock 0곳(만료 sweep을 transaction 범위로 바꿈), 시간 제한은 URL `options`가 아니라 DB 역할 설정으로, prepared statement는 `max_prepared_statements` > 0. 3.4 전제 참고 |

## 7. 이 문서의 한계

- 세션/일, 요청/세션, 피크계수는 **가정**이며 실제 트래픽 로그로 대체해야 합니다.
- `TPC_ATLAS`는 캐시 없는 단일 측정에서 나온 **하한**입니다.
- AWS 인스턴스 `max_connections` 기본값은 버전·파라미터 그룹에 따라 다르므로, 배포 시 실제 값을 확인해야 합니다.
- T3의 오토스케일 전제는 이 저장소에 아직 구현되어 있지 않습니다. 이 문서는 **목표 상태**를 정의하며, 도달 여부는 별도 측정으로 확인합니다.
