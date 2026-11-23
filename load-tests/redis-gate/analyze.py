#!/usr/bin/env python3
"""analyze.py <rundir>... -> one-line JSON-ish per run to stdout + per-run detail json file."""
import json, re, sys, os, collections, statistics

def load_run(d):
    rd = f"{d}/r/rate-2000"
    rep = json.load(open(f"{d}/r/report.json"))[0]
    o = {"label": os.path.basename(d)}
    if "error" in rep:
        o["error"] = rep["error"]
        return o
    k6s, k6e = map(float, open(f"{rd}/k6-window.txt").read().split()[:2])
    o["arm"] = rep["gate_mode"][0] if isinstance(rep.get("gate_mode"), list) else str(rep.get("gate_mode"))
    o["cfg"] = open(f"{rd}/gate-config.txt").read().strip() if os.path.exists(f"{rd}/gate-config.txt") else ""
    o["completed_per_s"] = rep["completed_per_s"]
    o["dropped"] = rep["dropped"]
    o["reserved"] = rep["reserved"]
    o["rejected_409"] = rep["rejected_409"]
    o["other"] = rep["other"]
    o["p99_all"] = rep["latency_all"]["p(99)"]
    o["p50_rej"] = rep["latency_rejected"]["med"]
    o["xact_per_order"] = rep["xact_per_order_raw"]
    o["loc_xact_per_order"] = rep["location_xact_per_order"]
    o["wf_rows"] = rep["row_count_deltas"]["order_inventory_reservation_workflow"]
    o["wf_rows_per_order"] = rep.get("workflow_rows_per_order")
    o["app_err"] = rep["app_error_lines"]
    o["invariant_ok"] = rep["invariant_ok"]
    o["nonterminal"] = rep["nonterminal_after_settle"]
    g = rep.get("gate_delta") or {}
    o["gate"] = {k: g.get(k, 0) for k in ("admitted", "rejected", "fail_open")}
    for k in ("app", "location", "k6", "redis", "order_pg"):
        c = rep.get(f"docker_cpu_pct_{k}")
        o[f"cpu_{k}"] = (round(c["avg"], 1), round(c["max"], 1)) if c else None
    # series
    rows = []
    p = f"{d}/series.csv"
    if os.path.exists(p):
        for ln in open(p).read().splitlines()[1:]:
            f = ln.split(",")
            if len(f) < 11:
                continue
            rows.append([float(f[0])] + [float(x) if x else 0.0 for x in f[1:]])
    o["k6_window"] = (k6s, k6e)
    if rows:
        inw = [r for r in rows if k6s - 1 <= r[0] <= k6e + 1]
        if inw:
            base = rows[[i for i, r in enumerate(rows) if r[0] >= k6s - 1][0] - 1 if [i for i, r in enumerate(rows) if r[0] >= k6s - 1][0] > 0 else 0]
            fo = collections.OrderedDict()
            prev = base
            for r in rows:
                if r[0] < k6s - 1:
                    prev = r
                    continue
                if r[0] > k6e + 3:
                    break
                dfo = r[3] - prev[3]
                if dfo > 0:
                    fo[round(r[0] - k6s)] = int(dfo)
                prev = r
            o["fail_open_by_t"] = dict(fo)
            first = next((r for r in rows if r[0] >= k6s - 1), None)
            last = [r for r in rows if r[0] <= k6e + 2][-1]
            o["fail_open_total_window"] = int(last[3] - first[3])
            o["fail_open_before_k6_total"] = int(first[3])  # during warmup
            dc = last[4] - first[4]
            du = last[5] - first[5]
            o["eval_calls_window"] = int(dc)
            o["eval_usec_per_call"] = round(du / dc, 1) if dc else None
            o["exists_calls_window"] = int(last[6] - first[6])
            o["load1_mean_window"] = round(statistics.mean(r[10] for r in inw), 2)
            o["series_n_window"] = len(inw)
            o["series_max_gap_s"] = round(max(b[0] - a[0] for a, b in zip(inw, inw[1:])), 2) if len(inw) > 1 else None
    # slowlog
    sl = f"{d}/redis-slowlog.last"
    if os.path.exists(sl):
        t = open(sl).read()
        m = re.match(r"LEN\s+(\d+)", t)
        o["slowlog_len"] = int(m.group(1)) if m else None
        us = [int(x) for x in re.findall(r"^\d+\n\d+\n(\d+)\n", t, re.M)]
        o["slowlog_top20_usec"] = us[:5]
    # app WARN
    al = f"{d}/app.log"
    if os.path.exists(al):
        c = collections.Counter()
        first_w = None
        for ln in open(al, errors="replace"):
            if " WARN " in ln or ln.startswith("WARN"):
                m = re.search(r"Admission gate[^:]*:\s*(\w+)\s*$", ln.strip())
                if m:
                    c["gate:" + m.group(1)] += 1
                else:
                    m2 = re.findall(r"\]\s+([A-Za-z][\w.$]+)\s", ln)
                    c["other:" + (m2[-1] if m2 else "?")] += 1
        o["warn_classes"] = dict(c.most_common(6))
        import datetime
        inw = outw = 0
        for ln in open(al, errors="replace"):
            if " WARN " in ln or re.search(r" ERROR ", ln):
                try:
                    t = datetime.datetime.strptime(ln[:19], "%Y-%m-%dT%H:%M:%S").replace(tzinfo=datetime.timezone.utc).timestamp()
                except ValueError:
                    continue
                if k6s - 1 <= t <= k6e + 1:
                    inw += 1
                else:
                    outw += 1
        o["warn_error_in_k6_window"] = inw
        o["warn_error_outside_window"] = outw
        o["error_lines_in_log"] = sum(1 for ln in open(al, errors="replace") if re.search(r" ERROR |Exception", ln))
    return o

if __name__ == "__main__":
    for d in sys.argv[1:]:
        o = load_run(d.rstrip("/"))
        json.dump(o, open(f"{d}/analysis.json", "w"), indent=1)
        print(json.dumps(o))
