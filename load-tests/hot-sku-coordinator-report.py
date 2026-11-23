#!/usr/bin/env python3
"""Aggregate the per-rate artefacts written by run-hot-sku-coordinator.sh into one table + report.json."""
import glob
import json
import os
import re
import sys


def read_snapshot(path):
    snap = {"tables": {}, "rows": {}, "gate": {}}
    for line in open(path):
        parts = line.rstrip("\n").split("|")
        key = parts[0]
        if key == "epoch":
            snap["epoch"] = float(parts[1])
        elif key == "xact":
            snap["xact"] = int(parts[1])
        elif key == "locxact":
            snap["locxact"] = int(parts[1])
        elif key == "wal":
            snap["wal_records"], snap["wal_bytes"] = int(parts[1]), int(parts[2])
        elif key == "tbl":
            snap["tables"][parts[1]] = tuple(int(x) for x in parts[2:5])
        elif key == "rows":
            snap["rows"][parts[1]] = int(parts[2])
        elif key == "gate":
            snap["gate"][parts[1]] = float(parts[2])
        elif key == "hikari_timeout_total":
            snap["hikari_timeout_total"] = float(parts[1])
    return snap


def parse_invariant(log_path):
    text = open(log_path, errors="replace").read()
    match = re.search(r'INVARIANT (\{.*?\})(?:"| source)', text)
    if not match:
        return None
    raw = match.group(1).replace('\\"', '"')
    try:
        return json.loads(raw)
    except ValueError:
        return None


def series(path, name):
    values = []
    if not os.path.exists(path):
        return values
    for line in open(path):
        parts = line.split()
        if len(parts) >= 3 and parts[1].split("{")[0] == name:
            try:
                values.append(float(parts[-1]))
            except ValueError:
                pass
    return values


def docker_cpu(path):
    result = {}
    if not os.path.exists(path):
        return result
    for line in open(path):
        parts = line.split()
        if len(parts) < 3:
            continue
        name, cpu = parts[1], parts[2].rstrip("%")
        try:
            result.setdefault(name, []).append(float(cpu))
        except ValueError:
            pass
    return result


def stats(values):
    if not values:
        return None
    return {"max": max(values), "avg": sum(values) / len(values), "n": len(values)}


def main():
    out, stock = sys.argv[1], int(sys.argv[2])
    rows = []
    for run_dir in sorted(glob.glob(os.path.join(out, "rate-*")), key=lambda p: int(p.rsplit("-", 1)[1])):
        rate = int(run_dir.rsplit("-", 1)[1])
        row = {"rate": rate}
        try:
            summary = json.load(open(os.path.join(run_dir, "summary.json")))
        except (OSError, ValueError):
            row["error"] = "no k6 summary"
            rows.append(row)
            continue
        duration = int(open(os.path.join(run_dir, "duration-seconds.txt")).read())
        iterations = summary["iterations"]
        before = read_snapshot(os.path.join(run_dir, "before.txt"))
        after = read_snapshot(os.path.join(run_dir, "after.txt"))
        idle_start = read_snapshot(os.path.join(run_dir, "idle-start.txt"))
        idle_end = read_snapshot(os.path.join(run_dir, "idle-end.txt"))
        idle_seconds = idle_end["epoch"] - idle_start["epoch"]
        window = after["epoch"] - before["epoch"]
        idle_rate = (idle_end["xact"] - idle_start["xact"]) / idle_seconds
        idle_wal = (idle_end["wal_bytes"] - idle_start["wal_bytes"]) / idle_seconds
        raw_xact = after["xact"] - before["xact"]
        corrected_xact = raw_xact - idle_rate * window
        wal_bytes = after["wal_bytes"] - before["wal_bytes"]
        corrected_wal = wal_bytes - idle_wal * window
        status = summary["statusCounts"]
        reserved = status.get("201", 0) + status.get("200", 0)
        rejected = status.get("409", 0)
        pending = status.get("202", 0)
        other = sum(v for k, v in status.items() if k not in ("200", "201", "202", "409"))
        state_counts = {}
        for line in open(os.path.join(run_dir, "workflow-states.txt")):
            if "|" in line:
                state, count = line.strip().split("|")
                state_counts[state] = int(count)
        table_deltas = {}
        for table, (ins, upd, dele) in after["tables"].items():
            b_ins, b_upd, b_del = before["tables"].get(table, (0, 0, 0))
            delta = (ins - b_ins, upd - b_upd, dele - b_del)
            if any(delta):
                table_deltas[table] = delta
        row_deltas = {t: after["rows"][t] - before["rows"].get(t, 0) for t in after["rows"]}
        invariant = parse_invariant(os.path.join(run_dir, "k6.log"))
        workflow_reserved = state_counts.get("RESERVED", 0)
        workflow_failed = state_counts.get("FAILED", 0)
        inv_ok = None
        if invariant and invariant.get("readable"):
            inv_ok = (
                invariant["capacityConserved"] and invariant["notOversold"]
                and reserved <= stock and workflow_reserved <= stock
                and invariant["reserved"] + invariant["claimed"] == workflow_reserved
            )
        actuator = os.path.join(run_dir, "actuator.log")
        cpu = docker_cpu(os.path.join(run_dir, "docker-stats.log"))
        app_cpu = next((stats(v) for k, v in cpu.items() if k.endswith("-app-1")), None)
        redis_cpu = next((stats(v) for k, v in cpu.items() if k.endswith("-redis-1")), None)
        gate_delta = {k: after["gate"].get(k, 0) - before["gate"].get(k, 0) for k in after["gate"]}
        mode_path = os.path.join(run_dir, "gate-mode.txt")
        gate_mode = open(mode_path).read().split() if os.path.exists(mode_path) else ["off", "none"]
        timeline = []
        if os.path.exists(os.path.join(run_dir, "k6.log")):
            for m in re.finditer(r"running \(([0-9.]+)s\), \d+/\d+ VUs, (\d+) complete", open(os.path.join(run_dir, "k6.log"), errors="replace").read()):
                timeline.append((float(m.group(1)), int(m.group(2))))
        stop_path = os.path.join(run_dir, "redis-stop-epoch.txt")
        redis_stop_rel = None
        if os.path.exists(stop_path):
            win = open(os.path.join(run_dir, "k6-window.txt")).read().split()
            redis_stop_rel = float(open(stop_path).read()) - float(win[0])
        loc_cpu = next((stats(v) for k, v in cpu.items() if k.endswith("-location-1")), None)
        k6_cpu = next((stats(v) for k, v in cpu.items() if "-k6-run-" in k), None)
        pg_cpu = next((stats(v) for k, v in cpu.items() if "order-postgres" in k), None)
        lat = summary["latencyMs"]
        row.update({
            "offered": summary["offeredRate"],
            "iterations": iterations,
            "completed_per_s": iterations / summary["testRunSeconds"],
            "completed_in_window_per_s": iterations / duration,
            "test_run_s": summary["testRunSeconds"],
            "dropped": summary["droppedIterations"],
            "vus_max": summary.get("vusMax"),
            "reserved": reserved, "rejected_409": rejected, "pending_202": pending, "other": other,
            "status_counts": status,
            "latency_all": lat["all"], "latency_rejected": lat["rejected"], "latency_reserved": lat["reserved"],
            "xact_raw": raw_xact, "xact_per_order_raw": raw_xact / iterations if iterations else None,
            "xact_per_order_net_of_idle": corrected_xact / iterations if iterations else None,
            "idle_xact_per_s": idle_rate, "window_s": window,
            "wal_bytes_per_order_net": corrected_wal / iterations if iterations else None,
            "wal_records_per_order": (after["wal_records"] - before["wal_records"]) / iterations if iterations else None,
            "location_xact_per_order": (after["locxact"] - before["locxact"]) / iterations if iterations else None,
            "workflow_states": state_counts,
            "workflow_failed": workflow_failed,
            "nonterminal_after_settle": int(open(os.path.join(run_dir, "nonterminal.txt")).read()),
            "table_tuple_deltas_ins_upd_del": table_deltas,
            "row_count_deltas": row_deltas,
            "hikari_pending": stats(series(actuator, "hikaricp_connections_pending")),
            "hikari_active": stats(series(actuator, "hikaricp_connections_active")),
            "hikari_max": stats(series(actuator, "hikaricp_connections_max")),
            "hikari_timeout_delta": (after.get("hikari_timeout_total", 0) - before.get("hikari_timeout_total", 0)),
            "process_cpu_usage": stats(series(actuator, "process_cpu_usage")),
            "tomcat_busy": stats(series(actuator, "tomcat_threads_busy_threads")),
            "gate_mode": gate_mode, "gate_delta": gate_delta, "docker_cpu_pct_redis": redis_cpu,
            "k6_timeline_cum_complete": timeline, "redis_stop_s_after_k6_launch": redis_stop_rel,
            "workflow_rows_per_order": row_deltas.get("order_inventory_reservation_workflow", 0) / iterations if iterations else None,
            "docker_cpu_pct_app": app_cpu, "docker_cpu_pct_location": loc_cpu, "docker_cpu_pct_k6": k6_cpu, "docker_cpu_pct_order_pg": pg_cpu,
            "invariant": invariant, "invariant_ok": inv_ok,
            "app_error_lines": int(open(os.path.join(run_dir, "app-error-lines.txt")).read().strip() or 0)
            if os.path.exists(os.path.join(run_dir, "app-error-lines.txt")) else None,
        })
        rows.append(row)

    with open(os.path.join(out, "report.json"), "w") as handle:
        json.dump(rows, handle, indent=2)

    def f(value, digits=1):
        return "n/a" if value is None else f"{value:.{digits}f}"

    header = ("offered | completed/s | dropped | RESERVED | 409 rejected | 202/other | p50/p95/p99 ms (all) | "
              "p50 ms rejected | tx/order raw | tx/order net | WAL B/order | app CPU% avg/max | pg CPU% avg/max | "
              "hikari pending max | k6 CPU% avg/max | location CPU% avg/max | invariant")
    print(header)
    for r in rows:
        if "error" in r:
            print(f"{r['rate']} | {r['error']}")
            continue
        la = r["latency_all"]
        cpu = r["docker_cpu_pct_app"]
        pg = r["docker_cpu_pct_order_pg"]
        hp = r["hikari_pending"]
        print(" | ".join([
            str(r["offered"]), f(r["completed_per_s"]), str(r["dropped"]), str(r["reserved"]), str(r["rejected_409"]),
            f"{r['pending_202']}/{r['other']}",
            f"{f(la['med'])}/{f(la['p(95)'])}/{f(la['p(99)'])}",
            f(r["latency_rejected"]["med"]),
            f(r["xact_per_order_raw"], 2), f(r["xact_per_order_net_of_idle"], 2), f(r["wal_bytes_per_order_net"], 0),
            f"{f(cpu['avg'], 0)}/{f(cpu['max'], 0)}" if cpu else "n/a",
            f"{f(pg['avg'], 0)}/{f(pg['max'], 0)}" if pg else "n/a",
            f(hp["max"], 0) if hp else "n/a",
            (lambda c: f"{f(c['avg'], 0)}/{f(c['max'], 0)}" if c else "n/a")(r["docker_cpu_pct_k6"]),
            (lambda c: f"{f(c['avg'], 0)}/{f(c['max'], 0)}" if c else "n/a")(r["docker_cpu_pct_location"]),
            str(r["invariant_ok"]),
        ]))
    print()
    for r in rows:
        if "error" in r:
            continue
        print(f"rate {r['rate']}: gate={r['gate_mode']} gate_delta={r['gate_delta']} workflow_rows/order={f(r['workflow_rows_per_order'], 3)} "
              f"redis_cpu={r['docker_cpu_pct_redis']}")
    for r in rows:
        if "error" in r:
            continue
        print(f"rate {r['rate']}: workflow_states={r['workflow_states']} nonterminal={r['nonterminal_after_settle']} "
              f"row_deltas={r['row_count_deltas']} tuple_deltas(ins,upd,del)={r['table_tuple_deltas_ins_upd_del']}")


if __name__ == "__main__":
    main()
