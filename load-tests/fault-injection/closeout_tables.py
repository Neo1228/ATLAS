#!/usr/bin/env python3
"""EXP-104 tables: classify cross-instance duplicate calls, relate takeovers to the lease time left at a pause, check the
injection of the SYN/request drops. Reads the run directories written by run-fault-injection.sh (rep-*/<label>/run-1).

  closeout_tables.py tables --root <out> [--only <label regex>]
  closeout_tables.py selftest

Cross-instance duplicate key (xdup): a location Idempotency-Key called from two different application addresses. Classes:
  T  lease takeover: the other application claimed the workflow while an old lease token was still set (claim with old_null=false)
  R  retry after release: no takeover, the other application claimed the workflow afterwards (attempts >= 2)
  W  warm-up leftover: the key already had a call before the measured load started (the lease log only holds the measured run's workflows)
  U  not explained by the lease log
Standard library only.
"""
import argparse
import collections
import json
import re
import statistics
import sys
from pathlib import Path

from audit_invariants import _json, _lines, _spread, parse_calls


def _vm_load(run_dir, name="env-before.txt"):
    try:
        found = re.search(r"vm_loadavg=([\d.]+)", (run_dir / name).read_text(encoding="utf-8"))
        return float(found.group(1)) if found else None
    except OSError:
        return None


def _mac_load(run_dir):
    try:
        found = re.search(r"load averages?:\s*([\d.]+)", (run_dir / "env-before.txt").read_text(encoding="utf-8"))
        return float(found.group(1)) if found else None
    except OSError:
        return None


def classify_xdup(calls, app_ips, claims, workflows, early_keys=frozenset()):
    """calls: parse_calls() rows of one location after the load started; claims/workflows: the lease-log.json lists.
    Returns one row per xdup key."""
    by_key = collections.defaultdict(list)
    for call in calls:
        by_key[call["key"]].append(call)
    workflow_of = {loc_key: w for w in workflows for loc_key in (w.get("loc_keys") or [])}
    claims_of = collections.defaultdict(list)
    for claim in claims:
        claims_of[claim["key"]].append(claim)
    rows = []
    for key, key_calls in by_key.items():
        ordered = sorted(key_calls, key=lambda c: c["begin"])
        app_calls = [c for c in ordered if c["ip"] in app_ips]
        if len({c["ip"] for c in app_calls}) < 2:
            continue
        first = app_calls[0]
        second = next(c for c in app_calls if c["ip"] != first["ip"])
        workflow = workflow_of.get(key)
        wf_claims = claims_of.get(workflow["key"], []) if workflow else []
        takeover = [c for c in wf_claims if not c["old_null"] and c["client"] == second["ip"]
                    and first["begin"] - 1 <= c["at"] <= second["begin"] + 1]
        later = [c for c in wf_claims if c["old_null"] and c["client"] == second["ip"] and c["at"] <= second["begin"] + 1]
        if key in early_keys and not workflow:
            kind = "W"
        elif takeover:
            kind = "T"
        elif later and workflow and (workflow.get("attempts") or 0) >= 2:
            kind = "R"
        else:
            kind = "U"
        rows.append({"key": key, "class": kind, "first_ms": round((first["end"] - first["begin"]) * 1000), "first_status": first["status"],
                     "second_status": second["status"], "gap_s": round(second["begin"] - first["end"], 2),
                     "attempts": (workflow or {}).get("attempts"), "claims": len(wf_claims), "takeovers": len(takeover),
                     "mapped": workflow is not None})
    return rows


def run_calls(run_dir):
    """The calls of both locations that belong to the load (after the warm-up), as {a: [...], b: [...]}, the application
    addresses, and the keys that were already called before the load started (the warm-up's workflows)."""
    requests = _lines(run_dir / "requests.jsonl")
    sent = [r["sent"] for r in requests if r.get("sent")]
    since = (min(sent) - 0.5) if sent else 0.0
    ips = _json(run_dir / "ips.json", {})
    result = {}
    early = set()
    for side in ("a", "b"):
        path = run_dir / f"location-{side}.log"
        text = path.read_text(encoding="utf-8", errors="replace") if path.exists() else ""
        every = parse_calls(text)
        early |= {c["key"] for c in every if c["begin"] < since}
        result[side] = [c for c in every if c["begin"] >= since]
    return result, {ips[n] for n in ("app", "app2") if n in ips}, early


def xdup_record(run_dir):
    calls, app_ips, early = run_calls(run_dir)
    lease = _json(run_dir / "lease-log.json", {})
    rows = []
    for side in ("a", "b"):
        rows += classify_xdup(calls[side], app_ips, lease.get("claims") or [], lease.get("workflows") or [], early)
    plain = [round((c["end"] - c["begin"]) * 1000) for side in ("a", "b") for c in calls[side]]
    return rows, plain


def pause_record(run_dir):
    """Per workflow that held a live lease right before a pause: lease time left when the pause began, and whether the other
    application took it over during the pause."""
    fault = _json(run_dir / "fault.json", {})
    leases = _json(run_dir / "exposure-leases.json", [])
    lease = _json(run_dir / "lease-log.json", {})
    ips = _json(run_dir / "ips.json", {})
    if not fault.get("strict_begin") or not leases:
        return None
    begin, end = fault["strict_begin"], fault["strict_end"]
    queried = fault.get("exposure_host_epoch", begin)
    taken = {c["key"] for c in lease.get("claims") or []
             if not c["old_null"] and c["client"] != ips.get("app") and begin - 1 <= c["at"] <= end + 2}
    rows = [{"left_s": (w["lease_until"] - w["db_now"]) - (begin - queried), "taken": w["key"] in taken} for w in leases]
    return {"pause_s": end - begin, "rows": rows}


def drop_record(run_dir):
    fault = _json(run_dir / "fault.json", {})
    begin = (fault.get("strict_begin") or 0) + (fault.get("skew") or 0)
    end = (fault.get("strict_end") or 0) + (fault.get("skew") or 0)
    syn_max = None
    for path in sorted(run_dir.glob("ss-*.txt")):
        for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
            parts = line.split()
            if len(parts) == 5 and begin <= float(parts[0]) <= end:
                syn_max = max(syn_max or 0, int(parts[2]))
    counters = 0
    path = run_dir / "iptables-counters.txt"
    if path.exists():
        for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
            parts = line.split()
            if len(parts) > 2 and parts[2] == "DROP" and parts[0].isdigit():
                counters += int(parts[0])
    calls = _json(run_dir / "calls.json", {})
    b = calls.get("b") or {}
    return {"syn_sent_max": syn_max, "drop_packets": counters, "b_strict": b.get("strict_window_calls"),
            "b_window": b.get("in_window_calls"), "b_strict_status": b.get("strict_window_status")}


def collect(root, only):
    groups = collections.defaultdict(list)
    for run_dir in sorted(Path(root).glob("rep-*/*/run-*")):
        label = run_dir.parent.name
        if only and not re.search(only, label):
            continue
        if not (run_dir / "result.json").exists():
            continue
        groups[label].append(run_dir)
    return groups


def tables(args):
    groups = collect(args.root, args.only)
    print("(b) cross-instance duplicate keys by class (A and B summed per run)\n")
    print("| condition | n | mac load before | VM load before | runs xdup>0 | xdup keys | T takeover | R retry | W warm-up | U unexplained | takeover claims "
          "| first call > 1.5 s (xdup / all calls) | min gap s (R) | control fired |")
    print("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |")
    for label in sorted(groups):
        runs = groups[label]
        per_run = [xdup_record(r) for r in runs]
        rows = [x for found, _ in per_run for x in found]
        counts = collections.Counter(x["class"] for x in rows)
        durations = [d for _, plain in per_run for d in plain]
        slow_all = sum(1 for d in durations if d > 1500)
        takeover = [(_json(r / "lease-claims.json", {}) or {}).get("takeover_claims") for r in runs]
        fired = [(_json(r / "calls.json", {}).get("control") or {}).get("fired") for r in runs]
        gaps = [x["gap_s"] for x in rows if x["class"] == "R"]
        print(f"| {label} | {len(runs)} | {_spread([_mac_load(r) for r in runs])} | {_spread([_vm_load(r) for r in runs])} | "
              f"{sum(1 for found, _ in per_run if found)}/{len(runs)} | {len(rows)} | {counts['T']} | {counts['R']} | {counts['W']} | {counts['U']} | "
              f"{sum(t or 0 for t in takeover)} | {sum(1 for x in rows if x['first_ms'] > 1500)} / {slow_all} of {len(durations)} | "
              f"{min(gaps) if gaps else '-'} | {sum(1 for f in fired if f)}/{sum(1 for f in fired if f is not None)} |")
    print("\n(c) takeover against the lease time left when the pause began (per workflow that held a live lease)\n")
    print("| condition | n | pause s (actual) | runs xdup>0 | leased workflows E | takeovers | left >= S+1: n / taken | left <= S-1: n / taken "
          "| in between: n / taken | converge s (min / med / max) | mac load before |")
    print("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |")
    for label in sorted(groups):
        records = [(r, pause_record(r)) for r in groups[label]]
        records = [(r, p) for r, p in records if p]
        if not records:
            continue
        high = [0, 0]
        low = [0, 0]
        mid = [0, 0]
        exposure = taken = xdup_runs = 0
        for run_dir, record in records:
            seconds = record["pause_s"]
            calls = _json(run_dir / "calls.json", {})
            if (calls.get("a") or {}).get("xdup_keys", 0) + (calls.get("b") or {}).get("xdup_keys", 0) > 0:
                xdup_runs += 1
            for row in record["rows"]:
                exposure += 1
                taken += row["taken"]
                bucket = high if row["left_s"] >= seconds + 1 else low if row["left_s"] <= seconds - 1 else mid
                bucket[0] += 1
                bucket[1] += row["taken"]
        converge = [_json(r / "result.json", {}).get("converge_seconds") for r, _ in records]
        print(f"| {label} | {len(records)} | {_spread([p['pause_s'] for _, p in records])} | {xdup_runs}/{len(records)} | {exposure} | {taken} | "
              f"{high[0]} / {high[1]} | {low[0]} / {low[1]} | {mid[0]} / {mid[1]} | {_spread(converge)} | {_spread([_mac_load(r) for r, _ in records])} |")
    print("\n(d) did the drop act? (SYN-SENT sockets of the applications towards location B inside the strict window, DROP packets, B calls)\n")
    print("| condition | n | injection seen (SYN-SENT max >= 1 and DROP >= 1; for the request drop DROP >= 1) | SYN-SENT max (min / med / max) "
          "| DROP packets (min / med / max) | four invariants | double 201 | B calls begun in the strict window (min / med / max) | B calls in the nominal window |")
    print("| --- | --- | --- | --- | --- | --- | --- | --- | --- |")
    for label in sorted(groups):
        if not label.startswith("e-"):
            continue
        runs = groups[label]
        records = [drop_record(r) for r in runs]
        syn = "syn" in label
        seen = sum(1 for d in records if d["drop_packets"] >= 1 and (not syn or (d["syn_sent_max"] or 0) >= 1))
        passed = sum(1 for r in runs if _json(r / "result.json", {}).get("pass_all"))
        doubles = sum(((_json(r / "calls.json", {}).get("a") or {}).get("double_201", 0)) + ((_json(r / "calls.json", {}).get("b") or {}).get("double_201", 0))
                      for r in runs)
        print(f"| {label} | {len(runs)} | {seen}/{len(runs)} | {_spread([d['syn_sent_max'] for d in records])} | "
              f"{_spread([d['drop_packets'] for d in records])} | {passed}/{len(runs)} | {doubles} | {_spread([d['b_strict'] for d in records])} | "
              f"{_spread([d['b_window'] for d in records])} |")
    return 0


def selftest(_args=None):
    app = {"10.0.0.2", "10.0.0.3"}
    calls = [
        {"ip": "10.0.0.2", "begin": 100.0, "end": 101.0, "status": 201, "key": "t:L"},   # taken over
        {"ip": "10.0.0.3", "begin": 112.0, "end": 112.2, "status": 200, "key": "t:L"},
        {"ip": "10.0.0.2", "begin": 100.0, "end": 102.5, "status": 201, "key": "r:L"},   # retried after release
        {"ip": "10.0.0.3", "begin": 104.0, "end": 104.1, "status": 200, "key": "r:L"},
        {"ip": "10.0.0.2", "begin": 100.0, "end": 100.1, "status": 201, "key": "u:L"},   # no lease record explains it
        {"ip": "10.0.0.3", "begin": 100.5, "end": 100.6, "status": 200, "key": "u:L"},
        {"ip": "10.0.0.2", "begin": 100.0, "end": 100.1, "status": 201, "key": "s:L"},   # one instance only
        {"ip": "10.0.0.2", "begin": 105.0, "end": 105.1, "status": 200, "key": "s:L"},
        {"ip": "10.0.0.2", "begin": 100.0, "end": 100.1, "status": 201, "key": "w:L"},   # warm-up leftover: called before the load too
        {"ip": "10.0.0.3", "begin": 100.4, "end": 100.5, "status": 200, "key": "w:L"},
        {"ip": "10.0.0.2", "begin": 100.0, "end": 101.0, "status": 201, "key": "x:L"},   # called before the load as well, but its workflow is in the lease log
        {"ip": "10.0.0.3", "begin": 112.0, "end": 112.2, "status": 200, "key": "x:L"},
        {"ip": "172.1.1.1", "begin": 100.0, "end": 100.1, "status": 409, "key": "f:L"},  # planted, not an application
        {"ip": "10.0.0.2", "begin": 101.0, "end": 101.1, "status": 200, "key": "f:L"},
    ]
    claims = [
        {"key": "wt", "old_null": True, "old_until": None, "at": 100.0, "client": "10.0.0.2"},
        {"key": "wt", "old_null": False, "old_until": 110.0, "at": 111.9, "client": "10.0.0.3"},
        {"key": "wx", "old_null": True, "old_until": None, "at": 100.0, "client": "10.0.0.2"},
        {"key": "wx", "old_null": False, "old_until": 110.0, "at": 111.9, "client": "10.0.0.3"},
        {"key": "wr", "old_null": True, "old_until": None, "at": 100.0, "client": "10.0.0.2"},
        {"key": "wr", "old_null": True, "old_until": None, "at": 103.9, "client": "10.0.0.3"},
    ]
    workflows = [{"key": "wt", "attempts": 2, "loc_keys": ["t:L"]}, {"key": "wr", "attempts": 2, "loc_keys": ["r:L"]},
                 {"key": "wu", "attempts": 1, "loc_keys": ["u:L"]}, {"key": "wx", "attempts": 2, "loc_keys": ["x:L"]}]
    rows = {r["key"]: r for r in classify_xdup(calls, app, claims, workflows, early_keys={"w:L", "x:L"})}
    expected = {"t:L": "T", "r:L": "R", "u:L": "U", "w:L": "W", "x:L": "T"}
    got = {k: r["class"] for k, r in rows.items()}
    if got != expected:
        raise SystemExit(f"self-test FAILED: classes {got}, expected {expected}")
    print("self-test ok")


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("selftest").set_defaults(handler=selftest)
    table_parser = sub.add_parser("tables")
    table_parser.add_argument("--root", required=True)
    table_parser.add_argument("--only")
    table_parser.set_defaults(handler=tables)
    args = parser.parse_args()
    return args.handler(args)


if __name__ == "__main__":
    sys.exit(main())
