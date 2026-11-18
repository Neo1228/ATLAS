# ATLAS 용량 산정 모델

이 문서는 "동시성 하나"가 아니라 **부하 → 자원 → 비용**의 전체 사슬을 명시적 가정 위에서 계산하는 방법을 정의합니다. 모든 수치는 가정과 공식을 함께 적고, 측정값과 추정값을 구분합니다.

## 1. 기호와 단위

| 기호 | 의미 | 단위 |
| --- | --- | --- |
| `DAU` | 일 활성 사용자 | 명 |
| `S` | 사용자 1명당 하루 세션 수 | 세션/명·일 |
| `R` | 세션 1회당 API 요청 수 | 요청/세션 |
| `P` | 피크 집중 계수 = 피크 1시간 요청량 / 하루 평균 1시간 요청량 | 배 |
| `L` | 평균 요청 지연 | 초 |
| `C` | 동시 처리 중인 요청 수 (in-flight) | 개 |
| `c` | 인스턴스당 vCPU | 개 |
| `U` | 목표 CPU 사용률 | 비율 |
| `TPC` | 코어당 처리량 (throughput per core) | req/s·core |

## 2. 부하 산정

```text
일 요청량        Q_day  = DAU × S × R
평균 RPS         λ_avg  = Q_day / 86,400
피크 RPS         λ_peak = λ_avg × P
```

`P`는 하루 중 가장 붐비는 1시간이 평균 1시간보다 몇 배인지를 뜻합니다. 업무 시간에 몰리는 서비스는 보통 3–5, 이벤트 오픈처럼 초 단위로 몰리면 그보다 훨씬 큽니다. **이 계수는 측정으로 정해야 하며, 여기서는 가정임을 명시합니다.**

## 3. 동시성 (Little's Law)

```text
C = λ × L
```

대기행렬 이론의 기본 항등식으로, 시스템에 들어온 요청 수 = 처리율 × 평균 체류 시간입니다. EXP-033 측정값을 대입하면:

| 기준 | 계산 | 결과 |
| --- | --- | --- |
| 130.25 RPS × p50 16 ms | 130.25 × 0.016 | 2.08 in-flight |
| 130.25 RPS × p95 37 ms | 130.25 × 0.037 | 4.82 in-flight |
| 130.25 RPS × p99 117.78 ms | 130.25 × 0.11778 | 15.34 in-flight |
| 130.25 RPS × 평균 21.6 ms | 130.25 × 0.0216 | 2.81 in-flight |

**해석:** 평균 동시성 2.81은 당시 6 VU 예산 안에 충분히 들어옵니다. 그런데도 드롭이 26/5,338(0.49%) 발생했습니다. 즉 지속 가능한 상한은 평균이 아니라 **꼬리 지연**이 결정합니다. p99 기준 15.34 in-flight는 6 VU로 감당할 수 없습니다. 이것이 "동시성만 보면 안 되는" 이유입니다.

필요한 동시성은 평균이 아니라 **SLO 백분위**로 잡아야 합니다.

```text
C_required = λ_peak × L_p99      (보수적)
C_typical  = λ_peak × L_p50      (하한, 여유 계산용)
```

## 4. 노드/코어 산정

코어당 처리량 `TPC`는 실측으로만 얻습니다. 측정된 (사용률, 처리량) 쌍에 선형 회귀를 적용해 목표 사용률에서의 TPC를 구합니다. 이는 Uber가 capacity recommendation engine에서 쓰는 방식과 같은 형태입니다(아래 참고).

```text
Cores_total     = λ_peak / (TPC × U)
Instances       = ceil(Cores_total / c)
```

`U`를 1.0이 아니라 0.5–0.7로 두는 이유는 **버스트 여유**입니다. Google SRE Workbook은 이벤트 전달 시스템에서 피크 시간 CPU의 50%를 목표로 프로비저닝한다고 기록합니다. 이는 보편 법칙이 아니라 그 시스템의 선택이며, ATLAS에서는 측정 후 결정해야 할 값입니다.

## 5. JDBC 연결 예산

```text
DB_connections_peak = Σ(instance_pool_max) + exporter + 운영 접속
DB_connections_peak ≤ max_connections × safety_factor
```

Hikari 기본 `maximumPoolSize`는 10입니다(`EXP-032`에 기록). 따라서 app replica `N`개면 최대 `10N`입니다. PostgreSQL `max_connections`가 60이고 exporter·psql·마이그레이션을 위해 일부를 남겨야 한다면, 안전 상한은 대략 `(60 - 8) / 10 ≈ 5` replica입니다.

중요한 점: **pool을 키우는 것은 처리량을 늘리지 않습니다.** DB가 병목이면 대기만 옮깁니다. 반대로 pool이 너무 작으면 CPU가 남아도 요청이 큐에서 기다립니다. 그래서 pool 크기는 `hikaricp_connections_pending`, `hikaricp_connections_acquire_seconds`, `hikaricp_connections_timeout_total`로 판단합니다.

## 6. 트랜잭션 (TPS)은 별도로 센다

읽기 요청과 쓰기 트랜잭션은 자원을 다르게 씁니다. 읽기는 DB 연결을 잠깐 빌리고, 쓰기는 락·WAL·fsync를 유발합니다.

```text
TPS_avg  = DAU × orders_per_user_per_day / 86,400
TPS_peak = TPS_avg × P
```

EXP-033의 체크아웃 시나리오에서 세션 1회는 주문 1건을 만들므로, 44.48 sessions/s는 곧 44.48 주문/s입니다. 이때 커밋 지표(commit/s)와 비교하면 애플리케이션 요청과 DB 트랜잭션의 비율을 알 수 있습니다.

## 7. 저장소와 캐시

읽기 경로에서 DB가 실제로 하는 일은 **버퍼 히트율**로 판단합니다.

```text
hit_ratio = blks_hit / (blks_hit + blks_read)
```

히트율이 높고 CPU가 낮으면 캐시 도입은 효과가 없습니다. 히트율이 낮고 `blks_read`가 지속되면 인덱스·workload shape·`shared_buffers`를 먼저 봅니다. Redis를 먼저 넣는 것은 이 진단을 건너뛰는 일입니다.

## 8. 시나리오 계산 (가정 명시)

가정: `S=2`, `R=40`, `P=5`, `L_p95=37 ms`. 아래 값은 **가정에 기반한 추정**이며 측정이 아닙니다.

| DAU | 평균 RPS | 피크 RPS | in-flight (p95 기준) | 130 RPS/노드 대비 replica |
| ---: | ---: | ---: | ---: | ---: |
| 10,000 | 9.26 | 46.30 | 1.7 | 0.36 |
| 100,000 | 92.59 | 462.96 | 17.1 | 3.55 |
| 1,000,000 | 925.93 | 4,629.63 | 171.3 | 35.54 |
| 10,000,000 | 9,259.26 | 46,296.30 | 1,713.0 | 355.44 |

이 표가 보여주는 것: 130 RPS/노드는 10만 DAU 근처에서 이미 부족해지고, 100만 DAU에서는 36대가 필요합니다. 그 규모에서는 `10 × 36 = 360` 연결이 되어 단일 PostgreSQL `max_connections=60`으로는 불가능합니다. 즉 **DAU가 한 자릿수 늘면 아키텍처가 바뀌어야 한다**는 것이 이 모델의 결론입니다.

## 9. 업계 사례와의 대응

| 사례 | 이 모델과의 관계 |
| --- | --- |
| Uber, [capacity recommendation engine](https://www.uber.com/blog/capacity-recommendation-engine/) | 사용률–TPC 선형 회귀, 목표 사용률 0.7 예시, `Cores_total = RPS_target / TPC_target` 형태 |
| Uber, [database overload](https://www.uber.com/blog/from-static-rate-limiting-to-intelligent-load-management/) | QPS 기반 제한의 한계를 인정하고 **동시성(in-flight)** 을 주 신호로 사용. Little's Law를 명시적으로 인용 |
| Google SRE Workbook, [capacity planning](https://sre.google/workbook/data-processing/) | 피크 시간 CPU 50% 목표라는 구체적 여유 정책 |
| Facebook, [throughput autoscaling](https://engineering.fb.com/2020/09/14/networking-traffic/throughput-autoscaling/) | 처리량 기반 오토스케일과 작은 단계·watchdog로 급격한 변동 억제 |
| Shopify, [BFCM readiness](https://shopify.engineering/bfcm-readiness-2025) | 피크 이벤트 전 load test·game day를 반복하고 병목을 미리 제거 |

공통점은 **측정 → 병목 특정 → 그 병목만 제거**입니다. 인프라를 먼저 늘리는 접근은 어느 사례에도 없습니다.

## 10. 이 모델의 한계

- `P`, `S`, `R`은 서비스마다 다르며 ATLAS의 실제 트래픽 로그가 없으므로 **가정**입니다.
- `TPC`는 하드웨어·JVM·워크로드에 따라 달라집니다. EXP-035에서 단일 노드와 분산 노드의 TPC를 각각 측정합니다.
- Little's Law는 안정 상태에서만 성립합니다. 부하가 급변하는 순간에는 순간 in-flight가 계산보다 커질 수 있습니다.
- 이 모델은 비용(예: replica 시간당 비용)을 포함하지 않습니다. 실제 의사결정에는 비용 대비 효과가 필요합니다.
