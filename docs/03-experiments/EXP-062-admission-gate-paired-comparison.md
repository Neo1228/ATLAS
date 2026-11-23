# EXP-062: 품절 음성 캐시 precheck와 Redis gate의 같은 세션 교차 비교

- 질문: 거절될 주문을 앞단에서 거르는 두 방식, Redis 없는 precheck(품절 음성 캐시)와 Redis gate는 같은 조건에서 얼마나 다른가? 이 호스트의 run 간 잡음은 어느 정도인가?
- 관련: [ADR-0066](../02-decisions/adr/ADR-0066-redis-admission-gate.md), [EXP-060](EXP-060-hot-sku-admission-gate.md), [점검표](experiment-checklist.md), [ADR-0068](../02-decisions/adr/ADR-0068-external-research-direction-review.md)
- 상태: 탐색 실험(팔당 3~6회, 단일 호스트). [EXP-060](EXP-060-hot-sku-admission-gate.md)의 약점(세션이 다른 기준선, 팔당 1~2회)을 점검표대로 보강한 설계다.

## 설계

- 같은 세션에서 run마다 새 compose 스택. 순서는 `A B B A B A A B A B B A`(A=precheck, B=Redis)로 교차했다. A/A 대조로 precheck를 2,000/s에서 연속 2회 더 돌렸다.
- 환경: Apple M3 Pro, Docker VM 8 vCPU. 컨테이너 CPU 한도는 app 3, order-postgres 2, **location 2**(EXP-060은 1), location-postgres 1, k6 1, Redis 1. 합계가 VM vCPU를 넘는다(precheck 9.25, Redis 10.25).
- 입력: 재고 100인 SKU 1개, 워밍업 10초, 측정 30초, 도착률 1,000/s와 2,000/s. 1,000/s는 팔당 6회, 2,000/s는 팔당 3회(남은 블록은 시간 때문에 생략).
- jar: precheck의 품절 음성 캐시(기본 TTL 1초)가 들어간 빌드다. Redis 팔은 이 시점의 Redis gate(반환 경로 `confirm`/`release` 추가 **이전** 빌드)다.
- 재현: `HOT_SKU_GATE=precheck|redis HOTSKU_COORD_RATES=<rate> HOTSKU_COORD_DURATION=30s HOTSKU_COORD_LOCATION_CPUS=2 load-tests/run-hot-sku-coordinator.sh`

## 결과(중앙값, 괄호는 최소~최대)

| 도착률 | 팔 | n | completed/s | dropped | 주문DB tx/주문 | location 호출(tx)/주문 | location CPU 평균 |
| ---: | --- | ---: | --- | --- | --- | --- | --- |
| 1,000 | precheck | 6 | 988 (937~996) | 314 (95~1,826) | 0.048 | 0.0255 | 17% (한도 200%) |
| 1,000 | Redis | 6 | 996 (982~999) | 60 (3~448) | 0.040 | 0.0082 | 3% |
| 2,000 | precheck | 3 | 1,964 (1,956~1,974) | 1,023 (727~1,274) | 0.023 | 0.0130 | 11% |
| 2,000 | Redis | 3 | 1,683 (1,501~1,944) | 9,384 (1,550~14,864) | 0.067 | 0.0133 | 28% |

- 처리량 비 Redis/precheck: 1,000/s는 중앙값 1.007(범위 0.986~1.066), 2,000/s는 0.857(0.760~0.994).
- A/A 대조(precheck 대 precheck, 2,000/s): 1,977/s와 1,894/s로 차이 4.2%, dropped는 654와 3,119로 5배 차이였다. 1,000/s precheck 6회도 937~996/s로 6% 퍼졌다. 이것이 이 호스트의 run 간 잡음이다.
- 불변식: 20개 run 모두 RESERVED=100, 용량 보존, 응답은 201/409뿐, 비종결 workflow 0.
- 둘 다 기준선(EXP-060: 거절 주문당 DB tx 약 5.9, 1,000/s에서 383~485/s로 포화)에 비해 거절 주문의 durable 쓰기와 order DB 부하를 없앴다.

## 해석

- **1,000/s: 두 방식은 잡음 안에서 같다.** 비 1.007은 위 잡음(4~6%) 안이다. 차이는 처리량이 아니라 비용에서 일관되게 나타났다. Redis는 location 호출이 요청당 0.0082로 precheck의 0.0255보다 낮고 location CPU도 낮다. precheck는 TTL마다 인스턴스당 한 번씩 location을 읽는 대가를 낸다.
- **2,000/s: precheck가 더 안정적이었다.** precheck 5회가 1,894~1,977/s로 좁은 반면 Redis는 1,501~1,944/s로 넓었다. Redis의 낮은 2회(1,501/s, 1,683/s)는 gate `fail_open`(309회, 440회), 앱 ERROR 로그, workflow 행 409·540(정상은 100), k6 CPU 평균 92%·86%를 동반했다. 즉 높은 부하와 CPU 과할당에서 Redis 명령이 시간 초과(기본 200ms)로 통과 처리돼 거절될 주문이 DB까지 내려간 것으로 보인다(원인은 로그와 증상의 일치로 본 추정이며 명령 지연을 따로 재지 않았다). 나머지 1회(1,944/s, fail_open 0)는 precheck와 같은 수준이었다.
- 이 결과로 Redis의 구조적 한계를 말할 수는 없다. 그보다 Redis gate가 부하에서 fail-open으로 빠지지 않게 다듬을 과제가 드러났다(아래).

## 이 실험에서 정해진 것

1. 기본 gate 후보는 **precheck(품절 음성 캐시)** 로 한다. 추가 인프라 없이 같은 효과(거절 주문의 durable 쓰기 제거)를 냈고, 2,000/s에서 더 안정적이었다.
2. Redis gate는 location 호출을 한 자릿수 배 더 줄이는 이점이 있으므로 인스턴스가 많거나 location 읽기 비용이 클 때를 위한 옵션으로 둔다. 선택 기준은 "TTL마다 인스턴스당 location 읽기 1회"가 문제가 되는 규모다.
3. Redis gate 보강 과제: ① 부하에서 fail-open이 나는 원인을 명령 지연으로 분리해 재고 timeout·breaker 값을 정한다. ② 반환 경로(`confirm`/`release`)와 pending 반영을 넣은 빌드로 같은 짝 비교를 반복한다. ③ 시계열(5초 버킷)로 fail-open이 시작되는 도착률을 찾는다.

## 한계

- 팔당 3~6회, A/A는 2회뿐이다. 고정 ABBA 순서이고 완전 무작위가 아니며 시간 추세는 교차로 상쇄했을 뿐 직접 재지 않았다.
- CPU 한도 합이 VM vCPU를 넘어 두 팔 모두 과할당이고 Redis 팔이 더 크다. k6 CPU 평균이 80%를 넘은 run은 14(92%), 17(86%)이고, 최대가 80%를 넘은 run도 있어 이 run들의 처리량은 부하 생성기의 영향을 받았을 수 있다.
- 재고 100인 단일 SKU, 30초 구간이다. 다수 SKU(Zipf)에서 음성 캐시와 Redis 카운터의 이득 차이는 측정하지 않았다.
- p99는 드롭된 반복이 빠진 완료분 값이라 표에 싣지 않았다.
