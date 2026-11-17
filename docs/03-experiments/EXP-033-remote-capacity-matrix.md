# EXP-033: existing PostgreSQL/Hikari capacity probe

**상태:** Two remote 4-vCPU synthetic runs complete · no production capacity/SLO claim

## 목적과 경계

Use existing Compose, k6, Prometheus, and Hikari metrics before changing pool sizing or adding cache infrastructure. Runs were executed on GitHub Actions, not the user's laptop. Workload is the existing one-seller checkout flow; it does **not** measure review/wishlist read QPS or repeated-key cache hit rate.

## Environment and method

- GitHub Actions `ubuntu-latest`; `docker info`: 4 CPUs, 15.61 GiB memory.
- Existing Compose stack: PostgreSQL 18.6, app observability profile, Prometheus, Postgres exporter, Mailpit, k6.
- Sequential rates, fresh DB/ephemeral services per run, 120 seconds each: 2.78 / 11.12 / 44.48 synthetic checkout sessions/s (1×/4×/16× EXP-016 baseline).
- Initial run `44155788679` used fixed VUs 1/1/4. Adjusted run `44164690046` used VUs 1/2/6 from observed per-rate p99 × arrival rate × 1.5 headroom.
- Prometheus scrape interval is 5 seconds. Hikari active/pending figures are maxima of sampled gauges, not continuous instantaneous peaks. Raw summaries: [initial](results/exp-033/initial-run-44155788679.json), [adjusted](results/exp-033/adjusted-run-44164690046.json).

## k6 results

| Offered sessions/s | VUs initial / adjusted | HTTP RPS initial / adjusted | Completed / planned initial → adjusted | Dropped initial → adjusted | Session p50 / p95 / p99 ms initial → adjusted | Checkout errors |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 2.78 | 1 / 1 | 8.27 / 8.28 | 334/334 → 334/334 | 0 → 0 | 28/55/72.01 → 26/57/71 | 0 / 0 |
| 11.12 | 1 / 2 | 33.02 / 32.98 | 1333/1335 → 1334/1335 | 2 → 1 | 18/39/61.68 → 19/41/69 | 0 / 0 |
| 44.48 | 4 / 6 | 130.49 / 130.25 | 5307/5338 → 5312/5338 | 31 → 26 | 13/31/89 → 16/37/117.78 | 0 / 0 |

VU headroom reduced the high-rate drops from 31 to 26, but **26/5,338 = 0.49%** of planned iterations still dropped. The configured constant-arrival rate was therefore not fully sustained; this run does not establish a clean capacity ceiling.

## Hikari and expiry metrics (adjusted run)

| Offered sessions/s | App target up | Hikari sampled active max | Pool max | Pending max | Timeouts | Expiry changed series | Expiry sweep duration max (admission / checkout / order) |
| ---: | ---: | ---: | ---: | ---: | ---: | --- | ---: |
| 2.78 | 1 | 1 | 10 | 0 | 0 | 0 / 0 / 0 | 17 / 181 / 179 ms |
| 11.12 | 1 | 1 | 10 | 0 | 0 | 0 / 0 / 0 | 28 / 164 / 167 ms |
| 44.48 | 1 | 2 | 10 | 0 | 0 | 0 / 0 / 0 | 23 / 190 / 189 ms |

`hikaricp.connections.active` is sampled at 5-second intervals, so brief peaks may be missed. These figures show **no observed Hikari queueing/timeouts in the sampled windows**, including the achieved ~130 HTTP RPS workload; they do not prove that the pool can never saturate. Hikari config was not overridden for this run; runtime metric reports max 10.

The run recorded one Prometheus application target (`up=1`) per scenario. Hikari timeouts stayed zero. Expiry workers had no due rows; the `changed{workflow}` counters now appear as explicit zero values. Timer values reflect idle sweeps and are not loaded expiry throughput.

The aggregate Prometheus HTTP p95 query mixes setup and all routes; it is not interpreted as a buyer-route SLO. k6 session p95 above is the primary workload measurement.

## Decision and limitations

- **Do not increase Hikari or add Redis/Caffeine from these results.** The sampled pool had 0 pending/0 timeouts and at most 2/10 active connections at the achieved rates; the workload is synthetic and generator drops persist at the top rate.
- **Do not add local cache yet.** Checkout does not exercise the review/wishlist existence query; actual read QPS, hot-key ratio, DB RTT, and cross-instance invalidation tolerance remain unknown. EXP-031's 0.064ms SQL-only existence query is not enough to infer an application cache benefit.
- No PostgreSQL CPU or connection headroom metric was persisted in this run, so this is not a database saturation test. The k6 generator shares the same 4-vCPU VM with app/Postgres/Prometheus and can itself become the limiter.
- This is evidence for a lower-bound synthetic workload, not users/sec, production throughput, HTTP SLO, or maximum capacity. Any pool/cache change requires a read-heavy workload and a repeatable on-runner result with no dropped iterations plus Hikari pending/timeout and DB CPU/connection headroom.

## Reproduction and source data

- Manual workflow only: [Application capacity probe](../../.github/workflows/application-load-probe.yml).
- Shell runner: [`run-exp033-capacity-matrix.sh`](../../load-tests/run-exp033-capacity-matrix.sh).
- Runs: [initial `44155788679`](https://github.com/Neo1228/ATLAS/actions/runs/44155788679), [adjusted `44164690046`](https://github.com/Neo1228/ATLAS/actions/runs/44164690046).
- Each scenario tears down Compose volumes; no user or production data is used.
