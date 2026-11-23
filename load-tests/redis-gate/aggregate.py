#!/usr/bin/env python3
"""aggregate.py <prefix>... : group runs by label prefix (strip trailing -N); print median(min~max) tables."""
import json, glob, os, re, statistics, sys
base = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", ".local", "redis-gate"))
groups = {}
for d in sorted(glob.glob(base + "/*/analysis.json")):
    o = json.load(open(d))
    if "error" in o:
        print("SKIP (no k6 summary):", o["label"])
        continue
    g = re.sub(r"-\d+$", "", o["label"])
    groups.setdefault(g, []).append(o)
def ms(vals, fmt="{:.0f}"):
    vals = [v for v in vals if v is not None]
    if not vals:
        return "-"
    return f"{fmt.format(statistics.median(vals))} ({fmt.format(min(vals))}~{fmt.format(max(vals))})"
want = sys.argv[1:]
for g, runs in groups.items():
    if want and not any(g.startswith(w) for w in want):
        continue
    print(f"== {g} n={len(runs)}")
    print(" completed/s", ms([r["completed_per_s"] for r in runs]), "| each", [round(r["completed_per_s"]) for r in runs])
    print(" dropped", ms([r["dropped"] for r in runs]), "| each", [r["dropped"] for r in runs])
    print(" fail_open(gate delta)", [int(r["gate"]["fail_open"]) for r in runs], "app_err", [r["app_err"] for r in runs], "wf_rows", [r["wf_rows"] for r in runs])
    print(" xact/order", ms([r["xact_per_order"] for r in runs], "{:.4f}"), " loc_xact/order", ms([r["loc_xact_per_order"] for r in runs], "{:.4f}"))
    for k in ("k6", "app", "location", "redis", "order_pg"):
        v = [r[f"cpu_{k}"] for r in runs if r.get(f"cpu_{k}")]
        if v:
            print(f" cpu_{k} avg", ms([x[0] for x in v], "{:.0f}"), "max", ms([x[1] for x in v], "{:.0f}"))
    print(" p99_all", ms([r["p99_all"] for r in runs]), "p50_rej", ms([r["p50_rej"] for r in runs], "{:.1f}"))
    print(" eval usec/call", [r.get("eval_usec_per_call") for r in runs], "exists calls", [r.get("exists_calls_window") for r in runs])
    print(" slowlog len", [r.get("slowlog_len") for r in runs], "load1", [r.get("load1_mean_window") for r in runs])
    print(" fail_open_by_t", [r.get("fail_open_by_t") for r in runs])
    print(" warn", [r.get("warn_classes") for r in runs], "in-window", [r.get("warn_error_in_k6_window") for r in runs], "outside", [r.get("warn_error_outside_window") for r in runs])
