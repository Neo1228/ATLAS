# EXP-XXX: 실험명
- 상태: ○ Planned
- 가설:
- 관련 요구사항·불변조건·결정:

## 환경·재현성
OS·version·configuration, 실행 명령, 반복 횟수.
## 데이터·부하
- Synthetic dataset 생성·가명 처리 방식.
- Offered load, 단위, Buyer journey, request/transaction mix, payload.
- Open/closed model, concurrency, think time, burst/ramp, duration.
- TPS는 transaction 범위, QPS는 DB query 범위 명시.
## 절차
재현 가능한 steps/commands.
## 지표·완료 기준
- Offered/achieved throughput, p95/p99 latency, error rate.
- Queue/backlog, saturation, 예상 invariant와 threshold.
## 결과
실측만 기록. 미실행: TBD.
## 분석·한계
결과 해석, confounders, 한계.
## 결정·후속 작업
상태 변경과 후속 Issue.
## 산출물
logs/charts/scripts/commit SHA.

실험 등록: [Experiment Registry](experiment-registry.md).
