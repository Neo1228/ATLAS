#!/usr/bin/env python3
"""Produce reservation workflow events at a fixed rate, straight into the order database.

For every tick it inserts one workflow in PENDING (event version 1) and, one second later,
moves it to RESERVED (event version 2). The trigger that the application relies on writes the
event row in the same transaction, so this is the same event source the relay reads; only the
HTTP and location-service hops are left out. The relay and Kafka path under test is untouched.

All statements go through one psql process (docker exec -i) in autocommit mode. The plan file
records what was sent, so the audit can compare it with what the database holds.
"""
import argparse
import json
import subprocess
import sys
import time
import uuid


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--container", required=True, help="order PostgreSQL container")
    parser.add_argument("--user", default="atlas")
    parser.add_argument("--database", default="atlas_order")
    parser.add_argument("--rate", type=int, required=True, help="new workflows per second")
    parser.add_argument("--duration", type=int, required=True, help="seconds of inserts")
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--plan", required=True, help="output JSON path")
    args = parser.parse_args()

    key_prefix = f"kof-{args.run_id}-"
    psql = subprocess.Popen(
        ["docker", "exec", "-i", args.container, "psql", "-X", "-q", "-v", "ON_ERROR_STOP=1",
         "-U", args.user, "-d", args.database],
        stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True)

    workflows = []
    total = args.rate * args.duration
    interval = 1.0 / args.rate
    start = time.time()
    max_late = 0.0
    # ticks 0..total-1 insert; the update for workflow k goes out at tick k + rate
    for tick in range(total + args.rate):
        due = start + tick * interval
        delay = due - time.time()
        if delay > 0:
            time.sleep(delay)
        else:
            max_late = max(max_late, -delay)
        statements = []
        if tick < total:
            workflow_id, order_id = str(uuid.uuid4()), str(uuid.uuid4())
            workflows.append(workflow_id)
            statements.append(
                "INSERT INTO order_inventory_reservation_workflow (workflow_id, order_id, "
                "idempotency_key, workflow_state, attempt_count, next_attempt_at, created_at, "
                f"updated_at) VALUES ('{workflow_id}', '{order_id}', '{key_prefix}{tick}', "
                "'PENDING', 0, now(), now(), now());")
        if tick >= args.rate:
            statements.append(
                "UPDATE order_inventory_reservation_workflow SET workflow_state = 'RESERVED', "
                f"updated_at = now() WHERE workflow_id = '{workflows[tick - args.rate]}';")
        try:
            psql.stdin.write("\n".join(statements) + "\n")
            psql.stdin.flush()
        except BrokenPipeError:
            break
    end = time.time()
    try:
        psql.stdin.write("SELECT 1;\n")
        psql.stdin.close()
    except BrokenPipeError:
        pass
    stderr = psql.stderr.read()
    exit_code = psql.wait()
    plan = {
        "run_id": args.run_id, "key_prefix": key_prefix, "rate": args.rate,
        "duration": args.duration, "workflows": len(workflows),
        "planned_events": 2 * len(workflows), "started_at": start, "ended_at": end,
        "max_tick_lateness_s": round(max_late, 3), "psql_exit": exit_code,
        "psql_stderr_lines": len([line for line in stderr.splitlines() if line.strip()]),
    }
    with open(args.plan, "w") as handle:
        json.dump(plan, handle)
    return 0 if exit_code == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
