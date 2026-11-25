# ADR-0084: Checkout hold TTL, AUTHORIZE-capture 목표와 단일 노드 데모 부하 계약을 목표(가정)로 정한다

- 상태: 채택(제안 PR, 병합은 통합 담당). 아래 p50/p95/p99와 처리량은 **측정값이 아니라 목표(가정)**다.
- 날짜: 2026-11-25
- 관련: [ADR-0047](ADR-0047-abandoned-approval-deadline.md), [ADR-0083](ADR-0083-admission-operational-values-and-lock-failure.md), [EXP-033](../../03-experiments/EXP-033-remote-capacity-matrix.md), [EXP-037](../../03-experiments/EXP-037-tiny-node-to-scale-out.md), [EXP-038](../../03-experiments/EXP-038-scale-out-limits-and-infra.md), [EXP-085](../../03-experiments/EXP-085-fault-injection-repeated.md), [EXP-086](../../03-experiments/EXP-086-admission-k-ttl-and-authorize-window.md), [EXP-090](../../03-experiments/EXP-090-admission-lock-failure-and-tombstone-size.md)
- 추적: [Q-004](../../01-requirements/requirements-index.md#assumptions-and-open-questions), [Q-008](../../01-requirements/requirements-index.md#assumptions-and-open-questions), [D-151](../../00-governance/decision-log.md), [D-152](../../00-governance/decision-log.md), [Issue #641](https://github.com/Neo1228/ATLAS/issues/641)

## 문제

D-030은 checkout 10분을 "운영 TTL 아님"으로, Q-008은 부하 profile을 "SLO 해석 금지"로 두었다. 그래서 값을 바꿀지 판단할 기준선(목표)이 없다. 실측 buyer 분포가 없다는 이유로 기준선까지 없는 상태가 이어졌다. 이 ADR은 **목표를 가정으로 적어** 이후 실측이 목표를 넘거나 못 미칠 때 바꿀 수 있게 한다.

## 사실 (라벨)

| 사실 | 라벨 | 출처 |
|---|---|---|
| checkout window `PT10M`, approval TTL `PT30M`, 복구 window 24시간(재시도 8회, 최대 backoff 15분) | 설정 읽기 | `application.yml`, EXP-086 |
| authorization 29일 유효, capture는 3일 honor period 안 권장, Sandbox `expiration_time` = 생성 + 29일(n=3) | 공식 문서 + Sandbox 측정 | EXP-086 |
| 10분·30분 창은 authorization 생성 전이라 honor를 쓰지 않는다. 24시간 복구 window는 honor 3일보다 약 48시간 짧지만 절대 상한으로 증명하지 못함 | 코드 읽기 | EXP-086 |
| 로컬 합성 AUTHORIZE-capture 지연 분포 | **없음(측정하지 않음)** | |
| EXP-033(GitHub Actions 4 vCPU/15.6 GiB, 앱 1대): 2.78/11.12/44.48 세션/s, HTTP 약 8.3/33/130 RPS, 세션 p95 57/41/37 ms, p99 71/69/118 ms, 오류 0, 44.48/s에서 드롭 26/5,338(0.49%) | 측정(원격 러너) | EXP-033 |
| EXP-037(Apple Silicon, Docker VM 8 CPU/13.5 GiB): 앱 1 vCPU는 20 it/s에서 p95 791 ms·드롭 2.1%, 2 vCPU는 20 it/s p95 7 ms·드롭 0%, 40 it/s p95 76 ms·드롭 0.5%, 앱 3 replica는 80 it/s p95 8 ms·드롭 0% | 측정(개발 머신) | EXP-037 |
| EXP-038(replica 3, 풀 5, 스텁 결제): 체크아웃 p95 122 ms(PgBouncer 없이)/25.7 ms(PgBouncer, 결제 활성), 결제 제출 p95 20~40.3 ms | 측정, 스텁 PayPal | EXP-038 |
| EXP-085(앱 1·location 2·PostgreSQL 3, 40 req/s 14초): DB 재시작 뒤 첫 201까지 0.31~0.54초, 응답 유실 요청의 수렴 꼬리 19.5~21.4초(lease 30초와 시간이 맞음, 직접 재지 않음), 응답받은 예약 유실 0 | 측정(공유 Docker VM) | EXP-085 |
| 한 Seller-SKU join 처리량 약 70/s, p99 1.36초, 최대 1.89초(풀 10) | 측정(공유 호스트) | EXP-090 |

## 결정

### 1. 시간 값 (Q-004)

| 값 | 결정 | 근거·가정 |
|---|---|---|
| checkout hold TTL | **10분 유지**(`atlas.checkout-window`, 연장 없음) | EXP-086: 시작 후 포기가 slot 시간의 34~76%를 10분 window 만료로 차지하나 window를 바꾸는 실험과 포기 시 즉시 반환 경로는 측정하지 않았다. 바꿀 근거가 없으면 유지가 가장 단순하다. 이제 "테스트 window"가 아니라 **V1 운영 값(가정)**이다. T2 p95 약 80초(가정 모형)의 7.5배 |
| approval TTL | **30분 유지** | authorization 생성 전 구간이라 honor 무관(코드 읽기) |
| 결과 불명 복구 window | **24시간 유지**, honor 3일 대비 **마진 48시간** | EXP-086. 마진이 24시간 미만이 되는 설정(복구 window ≥ 48시간)은 금지. 절대 상한은 증명하지 못했으므로 "capture는 authorize 후 72시간 안"을 운영 목표로 둔다(아래) |
| authorization 마진 | 29일 유효 대비 지금 구조는 최대 약 24시간 + 승인 전 40분만 쓴다. 마진 **28일 이상** | 계산(코드 읽기 기반) |

### 2. AUTHORIZE → capture 목표 (모두 **목표(가정)**, 측정 아님)

| 지표 | 목표 | 경계 정의 |
|---|---|---|
| 정상 경로 AUTHORIZE 제출 → capture 확정 지연 p50 / p95 / p99 | **2초 / 5초 / 10초** | 같은 요청 흐름 안(`continueCapture`), PayPal Sandbox·Live 왕복 포함. 현재 측정 없음(EXP-038은 스텁 왕복 14.6 ms) |
| capture 확정 비율(승인 완료 buyer 기준, 결과 불명 복구 포함) | 24시간 안 **99.9%** | 나머지는 `NEEDS_REVIEW`(운영자) |
| `NEEDS_REVIEW` 운영자 처리 시한 | authorize 후 **48시간** 안 | honor 3일에서 24시간 여유. 코드로 강제하지 않는다 |
| 오류 예산 | 5xx·시간 초과 **0.5%**(30일) | 아래 3절과 같은 값 |

이 목표들은 Sandbox 측정 하나 없이 정한 가정이다. 실제 지연이 생기면(첫 Sandbox/Live 판매 또는 `load-tests/paypal-real-fault/`) 목표를 측정값 + 여유로 바꾼다.

### 3. 단일 노드 데모 부하 계약 (Q-008, 가정)

**범위:** 앱 1대(2 vCPU/2 GiB 이상) + PostgreSQL 1대, 단일 호스트, PayPal은 Sandbox. 운영 용량 약속이 아니다.

| 항목 | 목표(가정) | 연결된 측정 |
|---|---|---|
| 지속 처리량 | **전체 도메인 세션 10/s(약 60 HTTP RPS)** | EXP-033 원격 러너 44.48 세션/s(130 RPS), 앱 1대, 오류 0, 드롭 0.49%. EXP-037 2 vCPU 40 it/s 드롭 0.5%. 목표를 측정 최댓값의 약 1/4로 둔 것은 공유 호스트·계획된 여유를 반영한 **판단**이다 |
| 버스트 | 3배(30/s) 60초 | EXP-037 2 vCPU는 40 it/s를 120초 견딤(드롭 0.5%) |
| 읽기 p95 / p99 | **100 ms / 300 ms** | EXP-038 목록 13, 검색 9, 상세 6 ms(p95, 측정) |
| 쓰기(checkout) p95 / p99 | **300 ms / 1,000 ms** | EXP-038 체크아웃 p95 122 ms(PgBouncer 없음), 25.7 ms(있음). 둘 다 스텁 결제 |
| 결제 제출 p95 | **1.5초**(실제 PayPal 왕복 포함) | 스텁 40.3 ms는 PayPal 지연이 아니다(EXP-038 명시) |
| 한 Seller-SKU join | **50/s**, p99 2초 | EXP-090 약 70/s·p99 1.36초(공유 호스트) |
| 오류 예산 | **0.5%**(5xx·시간 초과·드롭, 30일) | EXP-033 드롭 0.49%가 한계 부근이라 목표를 그보다 낮게 두지 않았다 |
| 불변식 | oversell 0, 응답받은 예약 유실 0, 중복 예약 0 | EXP-085 45회 모두 0, EXP-086 154회 모두 0 |
| 장애 복구 | DB 재시작 뒤 **5초 안** 첫 정상 응답, 응답 유실 요청의 수렴 **60초 안** | EXP-085 0.31~0.54초, 꼬리 19.5~25.5초(조건별 n=5, 공유 VM) |

측정 호스트가 서로 달라(원격 4 vCPU 러너, Apple Silicon Docker VM 8 CPU, 공유) 위 목표는 한 수치로 이어 붙인 것이 아니다. 어느 행도 단일 노드 데모에서 이 목표값으로 직접 재측정하지 않았다.

## 바꿀 조건과 되돌리기

| 결정 | 바꿀 조건 | 되돌리기 |
|---|---|---|
| checkout 10분 | 시작 후 포기율이 높아 접수가 재고의 80% 미만으로 끝남(ADR-0083), 또는 포기 시 즉시 반환 경로 도입 | 설정 `atlas.checkout-window`(코드 변경 없음) |
| AUTHORIZE-capture 목표 | 첫 Sandbox/Live 지연 측정이 p99 10초를 넘음 | 문서 값만 바꾼다 |
| 부하 계약 | 단일 노드 데모에서 목표값 재측정이 실패함, 또는 실제 트래픽 관측 | 문서 값만 바꾼다. 불변식 행은 바꾸지 않는다 |

## 후속 작업 단위

1. `load-tests/`에서 위 목표 세션률로 단일 노드 한 번 재측정하고 EXP로 남긴다(목표가 측정 위인지 확인).
2. Sandbox AUTHORIZE→capture 지연을 n≥30으로 재고 2절 목표를 갱신한다.
3. 코드·설정 변경 없음(이 ADR은 문서만 바꾼다).
