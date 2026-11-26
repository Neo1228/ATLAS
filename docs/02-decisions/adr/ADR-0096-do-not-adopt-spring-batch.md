# ADR-0096: Spring Batch를 도입하지 않는다

- 상태: Accepted(사용자 승인 전 에이전트 제안; 승인 여부는 확인하지 못함)
- 날짜: 2026-11-26
- 관련 결정: [ADR-0031](ADR-0031-bounded-application-scheduler.md), [ADR-0057](ADR-0057-financial-operation-outbox-and-payout-reconciliation.md)
- 근거: [EXP-107](../../03-experiments/EXP-107-balance-report-import-scale.md)

## 문제

Spring Batch를 도입하면 이점이 있는 곳이 있는가? 후보는 두 부류다. ① `@Scheduled` 메서드 15개(13개 클래스, 2026-11-26 `git grep` 기준). ② PayPal 잔액 보고서 가져오기(CSV 최대 10 MiB·10,000행). 새 dependency는 필요성과 대안을 확인해야 한다(`AGENTS.md`).

## 결정

도입하지 않는다. 아래 재검토 조건 중 하나가 측정으로 확인되면 다시 판단한다. Spring Batch가 주는 것(청크 단위 재시작, skip/retry 정책, 작업 이력)은 이 저장소의 두 부류가 이미 다른 방식으로 갖고 있거나 필요하지 않다.

### 작업 종류별 판단 (2026-11-26 `main` `4793d92c`의 코드 읽기와 키워드 분류)

| 부류 | 작업 | 이미 하는 방식 | Batch가 더하는 것 | 판단 |
|---|---|---|---|---|
| lease/claim 폴링 | `PaymentRecoveryWorker`, `PayPalWebhookRetryWorker`, `PayoutDispatchWorker`, `ReservationEventWorker`, `LocationReservationWorker`, `LocationRestockWorker`(6개) | 행 단위 claim과 lease, 같은 DB에서 여러 인스턴스가 경쟁해도 한 행은 한 번만 | 없음. 끝나지 않는 큐 소비라 "작업 인스턴스가 끝나면 재시작"이라는 모델이 맞지 않는다 | 해당 없음 |
| 만료·정리 sweep | `AdmissionTombstoneMaintenanceWorker`, `AuthThrottleMaintenanceWorker`, `InventoryReservationExpiryWorker`, `CheckoutFinalizationWorker` | 제한된 batch 크기, `SKIP LOCKED`, 지표 | 이력 테이블뿐. 한 번의 sweep이 몇 ms~초이고 다음 주기가 같은 일을 다시 한다 | 해당 없음 |
| 지표 gauge | `ScheduledExpiryMetricsWorker`, `ReturnInspectionMetrics`, `PayoutRiskMetrics` | 주기적 count 질의 | 없음 | 해당 없음 |
| 유한 데이터셋 가져오기 | 잔액 보고서(`PayPalBalanceReportRunIngestionService`) | 보고서 SHA-256으로 중복 방지, 메모리 안 파싱 후 **한 트랜잭션**으로 저장(`createRunIfAbsent`) | 청크 재시작, skip | 아래 측정 |

분류는 클래스 이름과 코드 키워드(`claim`, `lease`, `SKIP LOCKED`, `@Scheduled`)를 읽은 것이며, 각 클래스를 모두 줄 단위로 검증하지는 않았다.

### 잔액 보고서 측정 (EXP-107, 한도 크기 9,999행·518,147바이트, n=5)

| 측정 | 값 |
|---|---|
| 첫 가져오기 소요 | 0.9~3.6초(중앙값 2.1초) |
| 같은 보고서 다시 올림(중복) | 0.3~0.6초, 행 수 변화 0 |
| 힙 증가 | 12~67 MiB |
| 2,000행 보고서에서 500행째 삽입을 강제로 실패시킴 | 실행 수 4→4, 행 수 16,009→16,009(Spring 트랜잭션 안) |

- 한도 크기를 다시 가져오는 비용이 최악 약 4초다. 청크 단위 재시작이 줄여 줄 시간이 이보다 작다.
- 중간에 실패하면 아무것도 남지 않아 부분 실행 복구가 필요 없다. 같은 보고서를 다시 올리면 된다.
- 호스트 부하가 9~30으로 흔들려(다른 에이전트 실험과 같은 머신) 시간 값은 맥락용이다. 결론은 상한이 한도(10,000행)로 정해진 점과 원자성 확인에 기댄다.

## 대안과 버린 이유

| 대안 | 버린 이유 |
|---|---|
| 잔액 보고서 가져오기를 Spring Batch 잡으로 옮긴다 | 청크 재시작이 아낄 시간이 최악 약 4초이고 실패 시 남는 상태가 없다. 새 dependency, JobRepository(영속 저장 시 `BATCH_*` 테이블), 잡 파라미터와 중복 방지 규칙을 SHA-256 중복 방지와 이중으로 관리해야 한다 |
| 폴링 worker를 Batch의 `JobLauncher` 반복으로 바꾼다 | 끝나지 않는 lease 큐 소비를 "끝나는 잡"으로 흉내 내야 하고, 현재의 인스턴스 간 경쟁 규칙(ADR-0031)을 다시 검증해야 한다 |
| 도입하지 않는다 | **채택.** 풀려는 문제가 측정되지 않았다 |

## 결과와 한계

- 재검토 조건(하나라도 측정으로 확인되면): ① 한 번의 작업이 10분을 넘거나 그 실패 뒤 재실행 비용이 업무상 허용되지 않는 데이터셋이 생긴다(예: 판매자 수만 명 정산서 일괄 생성). ② 보고서 한도(10,000행·10 MiB)를 크게 올리는 요구가 생긴다. ③ 작업 실행 이력과 재시작을 운영자가 화면으로 요구한다.
- 한계: 이 판단은 단일 DB, 합성 CSV(전부 `Sale`, 일치 후보는 mock), PayPal 실제 보고서가 아닌 데이터의 측정에 기댄다. Spring Batch 6의 동작(재시작, 자원 없는 저장소)은 공식 문서 요약 수준으로만 확인했고 직접 시험하지 않았다. Spring Boot 4.1.2와의 호환 조합도 시험하지 않았다.
- 사용자 승인: 이 ADR은 에이전트가 근거를 정리한 제안이며 사용자 승인은 확인되지 않았다.

## 실패 경로와 되돌리기

문서만 바뀐다. 되돌릴 코드나 dependency가 없다. 결정을 뒤집으려면 새 ADR로 재검토 조건의 측정을 첨부한다.
