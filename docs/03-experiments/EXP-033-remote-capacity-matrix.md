# EXP-033: existing PostgreSQL/Hikari capacity probe

**상태:** Initial remote 4-vCPU matrix · adjusted-VU rerun pending · no production capacity/SLO claim

## 목적과 경계

Use the existing Compose+k6+Prometheus stack before changing Hikari pool sizing or adding caches. No local laptop load was run. The workload is the existing one-seller checkout path; it is **not** a review/wishlist cache benchmark.

## Environment and method

- GitHub Actions `ubuntu-latest`, `docker info`: 4 CPUs, 15.61 GiB memory.
- Existing Postgres 18.6, application `observability` profile, existing Prometheus/Hikari metrics, Mailpit test SMTP, pinned k6 image.
- Each rate starts with fresh PostgreSQL/Mailpit/Prometheus state; 120 seconds, one-seller profile.
- Initial VUs were 1 / 1 / 4 from EXP-016 calibration. Prometheus scrape interval is 5 seconds; Hikari values below are sampled maxima, not instantaneous peak capture.
- Run: [44155788679](https://github.com/Neo1228/ATLAS/actions/runs/44155788679); raw summarized data: [initial-run-44155788679.json](results/exp-033/initial-run-44155788679.json).

## Initial result

| Offered session rate | HTTP RPS | Completed / planned | Dropped iterations | Session p50 / p95 / p99 (ms) | Sampled Hikari active max / pending max / max pool / timeouts |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 2.78/s | 8.27 | 334 / 334 | 0 | 28 / 55 / 72.01 | 1 / 0 / 10 / 0 |
| 11.12/s | 33.02 | 1,333 / 1,335 | 2 | 18 / 39 / 61.68 | 1 / 0 / 10 / 0 |
| 44.48/s | 130.49 | 5,307 / 5,338 | 31 | 13 / 31 / 89 | 2 / 0 / 10 / 0 |

All runs: checkout errors `0`; Prometheus app target `up=1`; sampled `hikaricp.connections.pending=0`, timeouts `0`. No expiry rows changed in this workload. A 5s smoke before this matrix (run `44147765328`) verified Mailpit/signup/email verification/login and produced 5 checkout sessions with zero errors; its 5 samples are smoke evidence only, not an SLO.

## Interpretation and limits

- At the achieved highest rate (~130 HTTP RPS / 44.23 completed sessions/s), the observed Hikari active maximum was 2/10, pending 0, timeout 0. This is **no observed pool contention for this synthetic checkout workload**, not a universal pool-sizing recommendation or maximum sustainable throughput.
- The highest offered rate dropped 31/5,338 iterations (0.58%); the middle rate dropped 2/1,335 (0.15%). Fixed VUs were 4 and 1 respectively, so generator concurrency was insufficient for a clean arrival-rate guarantee at those levels. No ceiling or SLO conclusion is valid from the initial run.
- Hikari gauges are scraped every 5 seconds; brief connection peaks can be missed. The aggregate `http.server.requests` p95 mixes setup and all routes and is not used as checkout p95.
- `atlas.expiry.sweep.runs` and durations were scraped; no `.changed` series appeared because no rows changed and the worker had only registered that counter on positive changes. A follow-up registers stable zero-valued series.
- No local cache was tested. Product existence SQL cost from EXP-031 is 0.064 ms, actual review/wishlist QPS and cache hit ratio are unknown, and product availability is mutable. No Caffeine/Redis/Hikari change is justified by this run.

## Adjusted rerun

Issue #334 recalibrates fixed VUs using observed session p99 with 1.5× headroom: `ceil(rate × p99 × 1.5)` gives 1 / 2 / 6 for the three rates. It also registers `atlas.expiry.sweep.changed{workflow}` at zero. Rerun the same remote matrix after that change; retain the initial result rather than overwriting it.

## Next gate

Only consider a pool/cache change if the adjusted remote run shows sustained Hikari pending/acquisition timeout or repeatable p95/DB CPU pressure, and a read-heavy review/wishlist workload separately demonstrates a cache hit-rate/invalidation benefit. Existing metrics and run artifacts are sufficient for this next comparison; do not stress the local laptop.
