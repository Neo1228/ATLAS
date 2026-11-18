# EXP-034: 정상 부하·자원 효율 중심 SRE 신뢰성 검증 계획

**상태:** 계획 초안. 운영 목표와 배포 설정은 서비스 담당자 확인이 필요합니다.
**추적 이슈:** [#337](https://github.com/Neo1228/ATLAS/issues/337)
**결정:** 기존 애플리케이션·PostgreSQL·Prometheus를 활용해 관측과 실패 복구 검증을 보완합니다. 근거 없이 인프라를 추가하거나 최대 처리량을 목표로 튜닝하지 않습니다.

## 1. 목적과 제약

목표는 기록적인 처리량이 아니라 의도된 정상 부하에서의 신뢰성입니다. 현재 저장소만으로는 실제 운영 트래픽, 제품 SLO, 운영 DB 한계, 온콜 알림 경로를 확정할 수 없습니다. 따라서 측정된 사실과 검증되지 않은 가설을 분리하며, 가용성·지연 SLO를 임의로 만들지 않습니다.

- 이번 작업에서 고부하·로컬 스트레스 테스트를 추가로 실행하지 않습니다. EXP-033의 44.48 sessions/s 결과는 경계 관측일 뿐 운영 목표나 최대 용량 주장이 아닙니다.
- 기존 저부하 측정과 결정론적 통합/실패 테스트를 우선합니다. 부하 실행이 꼭 필요하면 트래픽 담당자가 대표성을 확인한 경우에만 격리된 remote runner에서 2.78 sessions/s 프로파일을 사용합니다.
- 지속적인 장애 징후와 하위 시스템 여유가 확인되기 전까지 Hikari pool 크기, retry claim 수, lease 기간, cache 동작, scheduler 주기를 바꾸지 않습니다.
- 운영 준비가 된 것처럼 보이게 하려고 Redis, broker, Alertmanager, 새 telemetry 서비스를 추가하지 않습니다.
- 매 poll 또는 매 scrape마다 추가 SQL을 실행하는 metrics는 피하고, 기존 처리 경로에 저카디널리티 metrics만 추가합니다.

## 2. 근거 목록: 확인된 사실과 미검증 가설

| 영역 | 확인된 근거 | 미확인 사항·가설 | 비용이 가장 적게 드는 다음 확인 |
|---|---|---|---|
| 정상 부하 checkout | EXP-033 remote 4-vCPU runner에서 각 부하를 120초 실행. 2.78 sessions/s에서는 334/334 완료, dropped 0, HTTP 오류 0. 5초 주기 Hikari 표본은 active=1, pending=0, max=10, timeouts=0. | 생성 checkout 시나리오와 요청률이 운영 트래픽을 대표하는지 불명확합니다. 5초 표본은 짧은 pool peak를 놓칠 수 있고 PostgreSQL CPU·lock wait·connection 여유는 측정되지 않았습니다. | 정상 트래픽 형태를 담당자에게 확인합니다. 대표성이 확인되면 낮은 부하만 재실행하고 DB-side CPU/connection/lock 지표를 함께 수집합니다. |
| 높은 부하 경계 관측 | 44.48 sessions/s에서 계획된 5,338개 중 26개가 dropped(0.49%)했고 HTTP 오류는 0이었습니다. EXP-033도 이를 capacity ceiling으로 주장하지 않습니다. | drop 원인은 분리되지 않았고 runner 경합 및 짧은 표본 주기가 교란 요인입니다. | 특정 용량 결정을 위해 더 싼 근거가 불가능하다는 승인된 질문이 없는 한 추가 peak test를 하지 않습니다. |
| Hikari / DB | 낮은 부하 표본에서는 pending connection과 timeout이 관측되지 않았습니다. 해당 테스트의 pool max는 10이었습니다. | 운영 Hikari max, PostgreSQL `max_connections`, 다른 client의 사용량, DB 여유는 배포 설정에 달렸으며 저장소 근거가 없습니다. | 실제 배포 설정을 확인하고 애플리케이션 pool 총량과 비애플리케이션 연결을 DB 한계와 대조합니다. |
| Payment recovery | 기존 worker는 poll마다 최대 10건을 제한적으로 claim하며 run/outcome/duration, job state, oldest due age metrics를 가집니다. 저장소 기본값은 worker 활성화지만 배포 override는 미확인입니다. | 저장소에 alert rule과 운영 runbook이 없어 대응 임계치가 정해져 있지 않습니다. metric이 없다는 사실만으로 queue 정체를 의미하지 않습니다. | 배포된 worker flag를 확인하고 통제된 due-job 통합 테스트에서 기존 oldest-due/state metrics를 검증합니다. |
| PayPal webhook retry | retry row는 PostgreSQL에 저장됩니다. claim은 `FOR UPDATE SKIP LOCKED`를 사용하고 poll 한 번에 due row 한 건을 선택합니다. 기본 poll interval은 1초입니다. | worker 전용 counter/duration/backlog age와 실제 arrival/service rate가 없습니다. 따라서 queue 안정성이나 delivery 목표는 코드만으로 결론 낼 수 없습니다. | 추가 SQL 없이 저카디널리티 claim/outcome/poll metrics를 추가하고 reschedule, stale lease, poll failure를 테스트합니다. cadence/batch 변경 전 실제 arrival rate와 oldest-due age를 확보합니다. |
| Expiry worker | 기존 expiry sweep은 run/duration/changed metrics를 기록하며 batch/time budget 및 progress key가 제한되어 있습니다. | alert 임계치, 운영 runbook, 운영 backlog-age 목표가 없습니다. | 기존 테스트에서 counter와 progress 동작을 확인하고, 담당자가 age 목표와 paging 경로를 정한 뒤 alert를 결정합니다. |
| 계정 이메일 | 이메일은 transaction commit 이후 전송됩니다. 전송 예외는 기록되며 사용자가 replacement token을 요청할 수 있습니다. | durable email outbox는 없으므로 commit 후 프로세스 장애가 발생하면 사용자가 다시 요청할 때까지 전송이 지연될 수 있습니다. 허용 여부는 제품 결정이며 장애율 근거는 확인되지 않았습니다. | SMTP 실패 후 resend를 테스트하고, outbox 검토 전에 제품 담당자에게 전달 시간 및 복구 요구사항을 확인합니다. |
| Read cache | EXP-032의 synthetic SQL-only `EXISTS` query는 약 0.064ms, 세 query 조합은 약 0.289ms였습니다. 이는 end-to-end cache 효과 측정이 아닙니다. 상품 availability는 변경 가능합니다. | read QPS/key 편향, cache hit ratio, end-to-end latency 효과, invalidation 및 허용 가능한 stale-read 시간이 확인되지 않았습니다. | 대표성 있는 read-heavy 프로파일과 freshness 계약이 생길 때까지 cache/Redis를 보류합니다. |
| Monitoring / 운영 | Compose는 로컬/격리 측정용 Prometheus/Grafana를 제공하며 capacity probe scrape interval은 5초입니다. 해당 설정에서 management endpoint는 내부 네트워크로 제한됩니다. | 체크인된 alert rule, Alertmanager routing, production scrape target, SLO, runbook 담당자, backup/restore 목표, 배포 manifest를 이번 저장소 검토에서 확인하지 못했습니다. 로컬 Compose는 production monitoring 증거가 아닙니다. | production 배포/monitoring 설정과 서비스·온콜 담당자를 확인합니다. 검증 전에는 production alert coverage를 주장하지 않습니다. |

## 3. 검증 순서

### 단계 A — 서비스 목표와 실제 설정 확정

Alert를 만들거나 운영 준비 완료를 선언하기 전에 서비스/제품 담당자가 다음을 제공합니다.

1. 사용자 관점 availability 및 latency 목표(대상 route/workload와 측정 기간 포함)
2. webhook 및 email 전달 기한/복구 기대치, payment·expiry backlog-age 목표
3. PostgreSQL durable job의 RTO/RPO 및 backup/restore 담당자
4. 운영 Hikari max pool, PostgreSQL connection limit, scheduler replica/flag, management bind/scrape 경로, SMTP 설정, alert notification route의 유효 배포값

위 정보가 없으면 추측한 SLO를 만들지 않고 명시적인 운영 준비 격차로 기록합니다.

### 단계 B — 저비용 코드 정확성 및 관측

1. 주입된 `MeterRegistry`를 사용해 기존 webhook retry poll의 duration, poll failure, claimed count, 제한된 outcome counter를 기록합니다. label은 고정된 소수의 outcome만 허용하며 event ID, order ID, exception 메시지, URL, 고객 정보를 넣지 않습니다.
- 다음 다섯 가지 metric series를 사용합니다: `atlas.paypal.webhook.retry.runs{outcome=success|failure}`, `atlas.paypal.webhook.retry.claimed`, `atlas.paypal.webhook.retry.outcomes{outcome=accepted|duplicate|rescheduled|rejected|transient_failure|stale_claim|record_failure}`, `atlas.paypal.webhook.retry.duration`, `atlas.paypal.webhook.retry.attempt.duration`.
- 기존 poll 경로에서만 갱신하며 주기적인 SQL query를 추가하지 않습니다. label에 event/order ID나 exception text를 넣지 않습니다.
1. poll마다 또는 scrape마다 별도 query를 추가하지 않습니다. 담당자가 delivery 목표상 backlog age를 요구하는 경우에만 후속 작업으로 검토하며, 먼저 index와 query 비용을 입증합니다.
1. 다음을 테스트합니다: due event가 없는 poll도 claim 0으로 관측됨, claim 수가 한 번만 증가함, success/duplicate, transient reschedule, permanent rejection, stale lease, repository poll failure가 의도한 outcome으로 기록됨, metrics가 retry/lease 의미를 바꾸지 않음.
1. 기존 내부 Prometheus endpoint에서 metrics를 확인하고 management port를 새로 외부에 노출하지 않는지 검증합니다.
1. 기존 통합 테스트 환경으로 제한된 repository/process interruption 뒤 worker 복구를 확인합니다. idempotency와 lease ownership은 유지하고 테스트를 위해 claim 크기나 주기를 높이지 않습니다.

### 단계 C — 필요할 때만 대표 정상 부하 검증

- 격리된 remote runner만 사용하며, 트래픽 담당자가 대표성을 확인한 경우에만 2.78 sessions/s 프로파일을 사용합니다.
- 기존 120초 프로파일은 smoke/regression check일 뿐 SLO나 용량 보장이 아닙니다. 결과 변동이 크거나 코드 변경의 end-to-end 확인이 필요한 경우에만 반복합니다.
- HTTP errors/drops/latency, Hikari active/pending/max/timeouts, PostgreSQL connections/CPU/locks, worker outcomes와 oldest-due metrics, Prometheus scrape health를 함께 수집합니다. 표본 누락을 여유로 오해하지 않도록 runner CPU/memory 및 scrape window도 기록합니다.
- 로컬 자원 사용이 예상 밖으로 증가하면 중단합니다. 로컬 Docker 부하 생성은 필요하지 않습니다.

### 단계 D — 실패 복구 및 운영 임계치

1. Webhook: transient failure가 reschedule되고, stale lease가 다른 worker의 claim을 완료하지 못하며, restart 후 durable row가 유지되고 business effect가 중복되지 않음을 확인합니다.
2. Payment recovery/expiry: 제한된 실패 이후 retry/progress를 검증하고 기존 metrics가 결과를 반영하는지 확인합니다.
3. Email: SMTP 실패가 이미 commit된 계정 상태를 rollback하지 않고 사용자 resend가 동작함을 확인합니다. 사용자에게 보이는 복구 경로를 문서화합니다.
4. Alert rule은 담당자가 목표와 notification route를 정한 뒤에만 도입합니다. 검토 후보는 지속적인 pool pending/timeout, worker poll failure, due-backlog age, Prometheus target down, expiry run failure이며 현재 승인된 임계치는 없습니다.

### 추가 검증: webhook lease 만료 경계

**후속 검증:** [#339](https://github.com/Neo1228/ATLAS/issues/339)

`PayPalWebhookRetryRepository`는 최대 8회 시도와 60초 lease를 사용하고, claim 시 시도 횟수를 증가시킵니다. `PayPalWebhookRetryWorker`는 `processVerified`를 동기 실행하며 처리 중 lease를 갱신하지 않습니다. 처리 시간이 lease를 넘으면 다른 instance가 같은 이벤트를 다시 claim할 수 있고, 만료된 8번째 lease는 원 worker가 끝나기 전에 `EXHAUSTED`로 바뀔 수 있습니다. 이는 코드에서 확인한 가능성이지 실측 장애는 아닙니다.

- `PaymentServiceImpl`의 capture 반영은 event ID 기반으로 중복 적용을 막지만, `PayPalWebhookService`는 그 transaction 이후 order/checkout reconciliation도 실행합니다. 이 후속 동작의 재실행 안전성은 별도로 검증해야 합니다.
- 기존 통합 테스트는 만료 lease 재claim, stale owner 차단, 8회 시도 소진을 다룹니다. 아직 처리 중인 handler가 lease 경계를 넘는 다중 instance 시나리오는 검증하지 않습니다.
- `atlas.paypal.webhook.retry.duration`은 claim query를 포함한 전체 poll 시간을 기록해 lease 사용 구간의 보수적 상한으로 볼 수 있습니다. `atlas.paypal.webhook.retry.attempt.duration`은 claim 결과를 받은 뒤 outcome을 기록할 때까지의 처리 시간을 재지만 claim query 시간은 제외하므로 실제 lease 시간보다 짧을 수 있습니다. 두 지표를 `atlas.paypal.webhook.retry.claimed`와 함께 비교해 60초 경계 위험을 살핍니다. `EXHAUSTED` 건수와 queue age는 현재 worker metrics에 없으며, claim 0이 exhausted row가 없음을 보장하지 않습니다.
- 60초 sleep이나 부하 테스트 대신 lease timestamp를 통제하는 결정론적 통합 테스트를 추가합니다. Worker A의 처리를 hold하고 lease 만료 후 Worker B가 claim하게 한 뒤 capture/reconciliation의 멱등성, stale owner 차단, 시도 횟수·최종 상태를 확인합니다.
- 대표 정상 조건에서 poll 시간과 60초 lease 사이의 여유를 확인합니다. 안전한 margin이 측정되기 전에는 lease 연장이나 renewal을 추가하지 않습니다. 초과가 관측되면 multi-instance 실패 의미를 포함해 bounded timeout과 lease renewal을 비교합니다.

## 4. 인프라 및 튜닝을 위한 명시적 결정 기준

- **Hikari 증가:** 정상 부하에서 pending connection/timeout 또는 설정 한도에 근접한 active connection이 지속되고, 측정된 PostgreSQL 여유가 전체 connection 증가를 감당할 때만 검토합니다.
- **Cache:** 대표 read workload에서 hot-key/query 병목이 확인되고, end-to-end A/B test가 합의된 SLI를 유의미하게 개선하며, memory budget과 freshness/invalidation 조건이 수용될 때만 검토합니다. SQL microbenchmark만으로는 통과할 수 없습니다.
- **Queue/broker/outbox:** 전달 기한 또는 측정된 durable backlog가 기존 PostgreSQL worker/resend 복구 경로로 충족되지 않을 때만 검토합니다. 먼저 event rate, oldest-age 분포, retention, 실패 모드, 운영 비용을 계량합니다.
- **새 alerting service:** SLO 임계치, routing, acknowledgement 기대치와 운영 담당자가 확정된 경우에만 도입합니다. Alert route 없이 Prometheus metric만 있는 것은 paging coverage가 아닙니다.

## 5. 완료 조건

Webhook metrics와 결정론적 실패 테스트가 구현되고, 정상 부하 프로파일의 대표성이 확인되거나 미확인으로 표시되며, 실제 배포/monitoring 담당 공백이 기록되고, 각 용량·인프라 변경에 측정 가능한 trigger가 있을 때 계획을 완료합니다. 누락된 담당자/설정 근거 없이 SLO, production readiness, 최대 용량 또는 production alert coverage를 주장하지 않습니다.
