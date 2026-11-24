# ADR-0072: 인프라 목록, 문서 근거 현황, 저장소에 올릴 것과 로컬에 둘 것의 기준

- 상태: Accepted(조사 결과 기록, 후속 정비는 아래 목록)
- 날짜: 2026-11-24
- 관련 결정: [ADR-0053](ADR-0053-shared-infra-adoption-gates.md), [ADR-0066](ADR-0066-redis-admission-gate.md), [ADR-0067](ADR-0067-event-log-kafka-relay.md), [ADR-0070](ADR-0070-parallel-backend-ci.md)
- 근거 실험: [EXP-064](../../03-experiments/EXP-064-location-fault-convergence.md), [EXP-066](../../03-experiments/EXP-066-db-outage-convergence.md)

## 문제

실험과 CI를 쌓는 동안 인프라가 늘었다. 어떤 인프라가 어디에 정의돼 있고, 왜 필요하며, 어떤 수치와 가정 위에 서 있는지를 한곳에서 볼 수 없다. 또 재현용 하네스가 저장소에 계속 쌓이면 main이 무거워지므로 무엇을 올리고 무엇을 로컬에 둘지의 기준이 필요하다.

## 조사 방법

`git ls-files`로 추적 파일 985개(조사 시점)를 읽고, compose 5개와 워크플로 4개를 파싱했다. 문서에서 인프라 이름을 정규식으로 센 값이라 오탐과 누락이 있을 수 있다(예: 한글 문장 속 표기). 수치는 방향을 보여 주는 값이다.

## 인프라 목록

| 구분 | 구성요소 | 정의 위치 | 용도 |
| --- | --- | --- | --- |
| 실행 | PostgreSQL 18.6 | `compose.yaml`, 각 `load-tests/compose-*.yaml`, Testcontainers | 주 저장소. order DB와 location DB를 분리해 실험에서는 최대 3개 |
| 실행 | `eclipse-temurin:25-jre` | `compose-inventory-location.yaml`, `compose-hot-sku-coordinator.yaml` | 독립 location 서비스, application 실행 |
| 실험 | Redis 7.4-alpine | `compose-hot-sku-coordinator.yaml`(profile `gate`) | admission gate. 옵트인([ADR-0066](ADR-0066-redis-admission-gate.md)) |
| 실험 | Kafka 3.9.1 | Testcontainers(`ReservationEventKafkaIntegrationTest`)만 | 이벤트 relay 검증. 상시 컨테이너 없음([ADR-0067](ADR-0067-event-log-kafka-relay.md)) |
| 부하 | k6 2.3.0 | `compose.yaml`(profile `loadtest`), `compose-hot-sku-coordinator.yaml`(profile `load`) | 부하 생성 |
| 장애 | Toxiproxy 2.12.0 | `compose.toxiproxy.yaml`, 테스트 | PostgreSQL·PayPal·SMTP 앞 TCP 프록시 |
| 관측 | Prometheus 3.5.0, Grafana 12.1.1, grafana-image-renderer, postgres-exporter 0.17.1, node-exporter 1.8.2 | `compose.yaml`(profile `observability`), `observability/`, `grafana/` | 메트릭 수집과 패널 |
| 보조 | Mailpit 1.34.0, nginx 1.29-alpine | `compose.yaml`, 각 compose | 메일 수신 확인, 카탈로그 edge cache 실험 |
| 빌드 | GitHub Actions | `.github/workflows/` 4개 | [ADR-0070](ADR-0070-parallel-backend-ci.md) |

compose 파일 5개의 서비스는 26개이고 이미지는 13종이다. 이 중 CPU·메모리 한도를 건 파일은 `compose-hot-sku-coordinator.yaml` 하나뿐이다(order DB 2 CPU/2 GiB, location DB 1 CPU/1 GiB, Redis 1 CPU/512 MiB, application 3 CPU/2 GiB, k6 1 GiB). 나머지 compose는 한도가 없어 실험 결과의 CPU 경합을 통제하지 못한다. 이 값들은 한도를 건 compose를 쓴 실험(EXP-053, EXP-060~063)에만 해당한다.

## 문서 근거 현황

| 항목 | 값 | 해석 |
| --- | --- | --- |
| 인프라 관련 파일(compose·workflow·load-tests·observability·grafana·scripts) | 52개 | |
| 이름으로 문서에 한 번도 언급되지 않은 파일 | 18개 | 아래 목록 |
| 문서 한 곳에서만 언급되는 파일 | 16개 | 근거가 한 문서에 몰려 있다 |
| 실험 문서 55개 중 "CPU" 단어로 호스트 자원을 서술한 것 | 32개(`vCPU`·`GiB`로 구체적 수치를 적은 것은 24개) | 나머지 23개에는 호스트 자원 서술이 없다. 계산만 하는 실험은 해당 없을 수 있다 |
| `node-exporter`를 다루는 ADR/EXP | 0개 | compose에는 있으나 도입 이유가 기록돼 있지 않다 |

문서에 언급되지 않은 18개: `grafana/dashboards/atlas-checkout.json`, `grafana/provisioning/dashboards/provider.yml`, `grafana/provisioning/datasources/prometheus.yml`, `load-tests/catalog-listing.js`, `load-tests/hot-sku-coordinator-report.py`, `load-tests/hot-sku-coordinator.js`, `load-tests/paypal-real-fault/package-lock.json`, `load-tests/paypal-real-fault/package.json`, `load-tests/redis-gate/queue.sh`, `load-tests/settlement-statement.js`, `load-tests/toxiproxy/paypal-capture-proxy.json`, `load-tests/toxiproxy/paypal-latency-250ms.json`, `load-tests/toxiproxy/paypal-proxy.json`, `load-tests/toxiproxy/postgres-proxy.json`, `load-tests/toxiproxy/postgres-reset.json`, `load-tests/toxiproxy/smtp-proxy.json`, `observability/prometheus.yml`, `scripts/verify-edge-cache.sh`.

이 표의 "문서 미언급 18개"는 이 ADR이 병합되기 전 기준이다. 병합된 뒤에는 이 문서가 18개 파일명을 나열하므로 같은 방법으로 다시 세면 0개가 된다. 이것은 문서화가 이루어졌다는 뜻이 아니라 목록이 이 문서에만 있다는 뜻이므로(다른 문서에서 한 번도 언급되지 않음), 재측정할 때는 이 ADR을 제외하고 센다.

## 확인된 불일치

- `observability/prometheus.yml`은 `pgbouncer-exporter:9127`을 scrape 대상으로 두지만 어떤 compose에도 `pgbouncer-exporter` 서비스가 없다. 기록된 PgBouncer 관련 실험(EXP-037~039)의 실행 스크립트는 `.gitignore`에 있어 저장소에 없다. 이 설정으로 `observability` profile을 켜면 이 대상은 항상 내려간 상태로 보인다. 지금은 코드를 바꾸지 않고 여기에 기록한다.
- 같은 증상의 문서 쪽 원인: `.gitignore`가 `load-tests/run-exp03[5-9]-*.sh`와 `load-tests/*single-to-distributed*.sh`를 제외하므로 EXP-035~039의 재현 스크립트는 저장소에 없다. 해당 실험 문서가 이를 명시하는지는 이 감사에서 확인하지 않았다.

## 올릴 것과 로컬에 둘 것의 기준

main은 가볍게 유지한다. 다음 조건에 하나라도 해당하면 코드는 저장소에 올리지 않고, 결과와 가정, 파라미터를 문서에 남긴다.

1. 실행하려면 추가 컨테이너나 외부 도구가 필요하고 CI에서 불안정할 수 있다(예: DB 끊김 주입).
2. 일회성 측정이 목적이고 회귀를 막는 테스트가 아니다.
3. 결과가 호스트 자원에 크게 의존해 CI runner에서 같은 값을 기대할 수 없다.

반대로 다음은 저장소에 둔다: 불변식을 지키는지를 빠르게(수 초) 결정적으로 확인하는 테스트, 그리고 재현 절차가 문서에 있는 부하 스크립트.

이 기준을 적용한 사례: [EXP-066](../../03-experiments/EXP-066-db-outage-convergence.md)의 하네스(759줄)는 올리지 않았다. 저장소 밖에 보관하고 로컬 `exclude`로만 가린다. 문서는 "저장소만으로 재현되지 않는다"를 명시한다.

## 가정 대장

| 가정 | 쓰인 곳 | 근거 | 약한 지점 |
| --- | --- | --- | --- |
| location 서비스는 멱등 키·취소 tombstone·상태 전이 규칙을 지킨다 | EXP-064, 065, 066의 메모리 모델 | `InventoryReservationServiceImpl` 코드를 읽고 같은 규칙으로 구현 | 모델과 실제 서비스가 같은지 자동으로 검증하지 않음 |
| 장애율 8%·5%·12% | EXP-064, 065, 066 | 없음(임의 값) | 실제 장애 분포가 아님 |
| backoff 20~100ms, lease 6초 | EXP-064, 066 | 테스트를 빠르게 하려고 줄임. 기본값은 1초~1분, 30초 | 수렴 시간은 운영 설정에 그대로 적용되지 않음 |
| 단일 호스트(Apple 실리콘, Colima VM) | 모든 부하 실험 | 실행 환경 | 다중 노드·네트워크 분리는 검증하지 않음 |
| 조건당 반복 1~6회 | 모든 실험 | 시간 제약 | 신뢰구간·검정 없음 |

## 후속 정비 목록(이번에 하지 않음)

1. 위 18개 파일 중 재현에 필요한 것은 해당 실험 문서에 연결하고, 필요 없는 것은 저장소에서 제거할지 판단한다. `load-tests`는 38개 파일 192KB이고 워크플로가 참조하는 것은 1개뿐이다. 제거는 실험 재현성을 줄이므로 문서별 판단이 먼저다.
2. 호스트 자원 서술이 없는 실험 문서 23개를, 부하 실험과 순수 계산 실험으로 사람이 읽고 다시 분류한다.
3. `node-exporter`의 도입 이유를 기록하거나 profile에서 제거한다.
4. `compose.toxiproxy.yaml`을 포함한 한도 없는 compose에 자원 한도를 거는 것이 결과를 바꾸는지 같은 입력으로 비교한다.

## 한계

이 문서는 조사 시점(main `1386fc3`, 이후 `ebb5209`까지 따라가 서비스 26개·이미지 13종·파일 52개 중 문서 미언급 18개를 다시 확인)의 스냅샷이다. 숫자는 정규식과 `git ls-files`로 센 값이며 문서를 사람이 읽고 판정한 것이 아니다.
