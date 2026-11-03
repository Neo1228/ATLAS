# EXP-013 — Gate 4 20-minute local calibration

**Status:** Completed; report-only. **Trace:** [Issue #81](https://github.com/Neo1228/ATLAS/issues/81). No production-capacity or SLO claim.

## Question and scope

At the previously selected 1× uniform arrival rate, does the current Buyer flow complete locally without HTTP failures, inventory invariant violations, or dropped iterations? Two 10-minute profiles: unconstrained baseline and 10%-stock/K=1 queue-backlog configuration. The 7-hour matrix, daily-reference run, 2×/4× stress, cloud, and live-PSP load are excluded. Existing deterministic integration tests remain the evidence for Fake PSP `PENDING`/`UNKNOWN` recovery.

## Environment and reproducibility

- Windows 10 host; Docker Desktop Linux engine `29.7.2`, 4 CPUs and `4,109,647,872` bytes memory. PostgreSQL `18.6` from `compose.yaml`; application and generator ran in the local Compose network. Shared-resource diagnostic, not an isolated benchmark.
- Application: built `application-module.jar` in `eclipse-temurin:25-jre` (`sha256:ecdc0adb37bde1a281c4d245a3cd535004b1fc8322d560f5b09463b248c81df0`). No host port was published.
- Generator: official `grafana/k6:2.3.0` (`sha256:2dd4756ec6e14e62778916e1eb850108e4b499304c02df56760fa0bdb0fba8`). No host k6 installation or application runtime dependency.
- Database isolation: disposable `atlas_gate4`; Flyway V1–V5 applied and Hibernate `ddl-auto=validate` passed. The final PostgreSQL database listing contained no `atlas_gate4`; the existing `atlas` database was not used for load records.
- Script: [`load-tests/gate4.js`](../../load-tests/gate4.js). It creates and opens a campaign, then performs one queue join, one checkout attempt, and payment only when stock is reserved. No polling, retries, think time, or synthetic PSP delay.
- Run IDs: `gate4-pilot-20261103`, `gate4-baseline-20261103`, `gate4-queue-backlog-20261103`. Arrival rate: `10,000 sessions / 60m = 2.7778 sessions/s`, uniform. The two load profiles total 20m; the separate one-VU preflight adds 60s. Each load profile ran 10m (~1,667 scheduled sessions). Baseline stock/K=`1,667/1,667`; queue profile stock/K=`167/1`; ticket TTL=`7,200s`.
- A separate one-VU, 60s full-flow pilot measured session p95 `97.56ms`; fixed `preAllocatedVUs = ceil(2.7778 × 0.09756) = 1`, with `maxVUs=1`. No automatic VU/resource expansion.

## Reproduction commands

Git Bash on Windows:

```sh
repoRoot="$(pwd -W)"
docker compose up -d --wait postgres
docker compose exec -T postgres createdb -U atlas atlas_gate4
./gradlew :application-module:bootJar

MSYS_NO_PATHCONV=1 docker run --rm --name atlas-gate4-app --network atlas_default \
  -e ATLAS_SERVER_ADDRESS=0.0.0.0 \
  -e ATLAS_DB_URL=jdbc:postgresql://postgres:5432/atlas_gate4 \
  -e ATLAS_DB_USER=atlas -e ATLAS_DB_PASSWORD=atlas-local-only \
  -v "$repoRoot/ATLAS-application/build/libs/application-module.jar:/app.jar:ro" \
  eclipse-temurin:25-jre java -jar /app.jar
```

In a second terminal, run the pilot and load profiles with pinned k6 image:

```sh
mkdir -p ATLAS-application/build/gate4-results
MSYS_NO_PATHCONV=1 docker run --rm --network atlas_default \
  -v "$repoRoot/load-tests/gate4.js:/gate4.js:ro" \
  -v "$repoRoot/ATLAS-application/build/gate4-results:/results" \
  -e ATLAS_BASE_URL=http://atlas-gate4-app:8080 \
  -e GATE4_PROFILE=baseline -e GATE4_MODE=pilot \
  -e GATE4_RUN_ID=gate4-pilot-20261103 \
  grafana/k6:2.3.0 run --summary-export=/results/gate4-pilot-20261103.json /gate4.js

MSYS_NO_PATHCONV=1 docker run --rm --network atlas_default \
  -v "$repoRoot/load-tests/gate4.js:/gate4.js:ro" \
  -v "$repoRoot/ATLAS-application/build/gate4-results:/results" \
  -e ATLAS_BASE_URL=http://atlas-gate4-app:8080 \
  -e GATE4_PROFILE=baseline -e GATE4_MODE=load \
  -e GATE4_RUN_ID=gate4-baseline-20261103 -e PREALLOCATED_VUS=1 \
  grafana/k6:2.3.0 run --summary-export=/results/gate4-baseline-20261103.json /gate4.js

MSYS_NO_PATHCONV=1 docker run --rm --network atlas_default \
  -v "$repoRoot/load-tests/gate4.js:/gate4.js:ro" \
  -v "$repoRoot/ATLAS-application/build/gate4-results:/results" \
  -e ATLAS_BASE_URL=http://atlas-gate4-app:8080 \
  -e GATE4_PROFILE=queue-backlog -e GATE4_MODE=load \
  -e GATE4_RUN_ID=gate4-queue-backlog-20261103 -e PREALLOCATED_VUS=1 \
  grafana/k6:2.3.0 run --summary-export=/results/gate4-queue-backlog-20261103.json /gate4.js

docker stop atlas-gate4-app
docker compose exec -T postgres dropdb -U atlas atlas_gate4
```

The output directory is under Gradle `build/` and excluded from Git; only the summarized report is committed.

## Results

| Run ID / profile | Iterations started / dropped | Tickets | Paid | Stock unavailable | WAITING / checkout 409 | HTTP requests / failed | Session p50 / p95 |
|---|---:|---:|---:|---:|---:|---:|---:|
| `gate4-pilot-20261103` (60s, 1 VU) | 1,265 / 0 | 1,265 | 1,265 | 0 | 0 / 0 | 3,797 / 0 | 36.97 / 97.56 ms |
| `gate4-baseline-20261103` (10m) | 1,661 / 6 | 1,661 | 1,661 | 0 | 0 / 0 | 4,985 / 0 | 39.69 / 99.61 ms |
| `gate4-queue-backlog-20261103` (10m) | 1,667 / 0 | 1,667 | 167 | 1,500 | 0 / 0 | 3,503 / 0 | 21 / 57 ms |

The baseline scheduled 1,667 sessions; k6 recorded six `dropped_iterations` (0.36%) and no failed HTTP requests. All 1,661 started sessions reached PAID. The queue profile completed all 1,667 iterations without HTTP failures; it exhausted its deliberately limited stock but did **not** create queue backlog.

### Database snapshot

| Profile | Initial capacity | Available | Reserved | Claimed | Conservation |
|---|---:|---:|---:|---:|---|
| Pilot | 1,667 | 402 | 0 | 1,265 | true |
| Baseline | 1,667 | 6 | 0 | 1,661 | true |
| Queue-backlog configuration | 167 | 0 | 0 | 167 | true |

All three snapshots satisfy `available + reserved + claimed = initial_capacity`. Final states matched the load output: baseline had 1,661 `CHECKOUT_SUBMITTED` tickets, `PAID` orders, and `CAPTURED` payments. The queue configuration had 167 `CHECKOUT_SUBMITTED`/`PAID`/`CAPTURED` flows and 1,500 `CHECKOUT_FAILED`/`FAILED` stockouts. No `WAITING` ticket or checkout `409` was present.

### Point-in-time Docker samples

| Run sample | App CPU / memory | PostgreSQL CPU / memory | k6 CPU / memory |
|---|---:|---:|---:|
| Pilot | 50.55% / 345.8 MiB | 12.96% / 88.19 MiB | 2.03% / 27.94 MiB |
| Baseline | 10.91% / 335.1 MiB | 3.85% / 86.57 MiB | 2.09% / 32.14 MiB |
| Queue-backlog configuration | 4.62% / 335.6 MiB | 3.29% / 87.42 MiB | 1.74% / 11.04 MiB |

These are `docker stats --no-stream` snapshots, not peak or time-series measurements.

## Interpretation and limits

The selected 1× rate spaces arrivals about `360ms` apart, while measured session p95 was `57–100ms`. With one fixed VU, a session normally finished before the next arrival; the K=1 queue profile therefore did not test concurrent queue contention. It tested low-rate stock exhaustion and preserved inventory conservation. A real contention profile would need a separately selected higher arrival rate or longer checkout-slot hold; neither is inferred or added here.

`dropped_iterations` in the baseline was six; its cause was not isolated. Fake PSP default success is immediate and does not model a real provider. The daily reference, 2×/4× matrix, queued-user wait/poll behavior, production sizing, and cross-machine comparison remain unmeasured. Results apply only to this Windows/Docker environment.
