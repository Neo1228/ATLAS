# ADR-0042: 용량 측정은 고정된 노드 스펙에서 단일→분산 순서로 한다

- 상태: Proposed
- 날짜: 2026-11-18
- 추적: [Issue #342](https://github.com/Neo1228/ATLAS/issues/342)

## 맥락

EXP-033은 원격 GitHub Actions 4 vCPU 러너에서 체크아웃 부하를 측정해 약 130 HTTP RPS와 0.49% 드롭을 기록했습니다. 이 결과는 유용했지만 세 가지가 부족했습니다.

1. **자원 귀속 불가.** k6 생성기, 애플리케이션, PostgreSQL, Prometheus가 같은 4 vCPU를 공유했습니다. 드롭이 애플리케이션 CPU 때문인지 생성기 때문인지 알 수 없습니다.
2. **DB 신호 부재.** Hikari active max 2/10, pending 0만 기록됐고 PostgreSQL CPU·연결·버퍼 지표는 저장되지 않았습니다.
3. **개선 비교 불가.** "무엇을 바꾸면 얼마나 좋아지는가"를 말하려면 동일 조건의 두 측정이 필요합니다.

동시에 로컬 개발 장비(11 CPU / 18 GiB)를 전부 쓰는 측정은 재현이 불가능합니다. 개발자마다 다른 결과가 나오고, 그 수치는 ATLAS가 아니라 그 장비를 설명합니다.

## 결정

1. **노드 스펙을 명시적으로 고정한다.** `.local/compose.capacity-node.yaml`에 애플리케이션 `cpus: 2.0` / `mem_limit: 2g`, PostgreSQL `cpus: 2.0` / `mem_limit: 2g` / `max_connections=60` / `shared_buffers=256MB`를 선언한다. 실험 문서는 이 값을 "노드 스펙"으로 인용한다.
2. **단일 노드에서 먼저 한계를 재현한다.** 포화 신호(드롭, p95 급증, Hikari pending, PG 연결·버퍼)를 지표로 특정한 뒤에만 확장을 논한다.
3. **분산 단계는 애플리케이션 replica만 늘린다.** `.local/compose.capacity-distributed.yaml`은 동일한 per-container 스펙의 replica 3개를 nginx round-robin 뒤에 둔다. PostgreSQL은 그대로 둔다. 따라서 개선 폭은 "replica 수"에 귀속된다.
4. **인스턴스별로 scrape한다.** `observability/prometheus-distributed.yml`은 `app-a`/`app-b`/`app-c`를 각각 target으로 등록하고 `instance_name` 라벨을 붙인다. 합산 평균은 포화를 숨기기 때문이다.
5. **패널 캡처를 코드로 남긴다.** `grafana/dashboards/atlas-capacity.json`과 `scripts/capture-grafana-panels.mjs`(로컬 전용)로 지표별 창을 고정 캡처하고, 창·크기·해시를 `manifest.json`에 기록한다.

## 근거

- **Little's Law**: EXP-033의 130.25 RPS에서 평균 동시성은 2.81이지만 p99 기준으로는 15.34다. 6 VU 예산으로는 꼬리 지연을 감당할 수 없고, 이것이 0.49% 드롭의 설명이 된다. 상한은 평균이 아니라 꼬리가 정한다.
- **Uber**: 정적 QPS 제한의 한계를 인정하고 in-flight 동시성을 주 신호로 삼는다. capacity recommendation은 사용률–TPC 회귀에 목표 사용률을 적용해 코어 수를 계산한다.
- **Google SRE Workbook**: 피크 시간 CPU 50%를 목표로 프로비저닝한 구체적 사례가 있다. 이는 보편 법칙이 아니라 정책 값이며, ATLAS도 측정 후 자체 값을 정해야 한다.
- **Facebook**: 처리량 기반 오토스케일에서도 작은 단계와 watchdog으로 급변을 억제한다. 즉 확장은 "크게 한 번"이 아니라 "측정하며 조금씩"이다.

## 기각한 대안

| 대안 | 기각 이유 |
| --- | --- |
| 로컬 전체 자원으로 측정 | 재현 불가. 비교 기준이 사라짐 |
| 처음부터 다중 replica로 측정 | 병목 귀속 불가. "빨라졌다"만 남음 |
| DB도 함께 확장 | 앱과 DB 중 무엇이 효과를 냈는지 구분 불가 |
| 캐시/큐/read replica 선도입 | EXP-032에서 필요성 근거가 아직 없음. 원인을 가림 |
| Hikari pool 확대 | DB 병목 시 대기를 옮길 뿐. 처리량 증가 아님 |
| 스크린샷 수동 첨부 | 창·크기·버전이 사라져 재현 불가 |

## 결과와 한계

- 이 결정은 **측정 방법**에 대한 것이며, 프로덕션 토폴로지를 확정하지 않는다. 3 replica는 비교를 위한 실험이고 서비스 목표가 아니다.
- 컨테이너 CPU 제한은 cgroup 기반이므로 호스트 스케줄링 영향을 완전히 제거하지 못한다.
- `cpus: 2.0`은 2코어 상한이지 코어 고정(pinning)이 아니다. 캐시 지역성 효과는 측정하지 않는다.
- 단일 노드 결과를 분산 환경의 SLA로 일반화하지 않는다.
