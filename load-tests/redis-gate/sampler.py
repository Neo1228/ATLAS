#!/usr/bin/env python3
"""1s sampler: app gate counters + Redis commandstats/slowlog. usage: sampler.py <outdir>"""
import os, re, subprocess, sys, time, urllib.request
out = sys.argv[1]
os.makedirs(out, exist_ok=True)

def sh(*a, t=3):
    try:
        return subprocess.run(a, capture_output=True, text=True, timeout=t).stdout
    except Exception:
        return ""

def find(service):
    for n in sh("docker", "ps", "--filter", "name=atlas-hotsku-coord", "--filter",
                f"label=com.docker.compose.service={service}", "--format", "{{.Names}}").split():
        return n
    return None

app = redis = mgmt = logproc = None
series = open(f"{out}/series.csv", "w", buffering=1)
series.write("epoch,admitted,rejected,fail_open,eval_calls,eval_usec,exists_calls,get_calls,set_calls,ops_per_sec,load1\n")
configured = False
nxt = time.time()
while True:
    nxt += 1.0
    if app is None:
        app = find("app")
        if app:
            m = re.search(r":(\d+)", sh("docker", "port", app, "8081/tcp"))
            mgmt = m.group(1) if m else None
    if redis is None:
        redis = find("redis")
    if app and logproc is None:
        logproc = subprocess.Popen(["docker", "logs", "-f", "--timestamps", app], stdout=open(f"{out}/app.log", "w"), stderr=subprocess.STDOUT)
    if app and mgmt:
        try:
            body = urllib.request.urlopen(f"http://127.0.0.1:{mgmt}/actuator/prometheus", timeout=1.5).read().decode()
            g = {}
            for line in body.splitlines():
                m = re.match(r'atlas_order_admission_gate_total\{[^}]*result="(\w+)"[^}]*\}\s+([0-9.eE+]+)', line)
                if m:
                    g[m.group(1)] = float(m.group(2))
            ev = [0, 0, 0, 0, 0, 0]
            ops = ""
            if redis:
                if not configured:
                    sh("docker", "exec", redis, "redis-cli", "CONFIG", "SET", "slowlog-log-slower-than", "2000")
                    sh("docker", "exec", redis, "redis-cli", "CONFIG", "SET", "slowlog-max-len", "256")
                    configured = True
                cs = sh("docker", "exec", redis, "redis-cli", "INFO", "commandstats")
                def cmd(name):
                    mm = re.search(rf"cmdstat_{name}:calls=(\d+),usec=(\d+)", cs)
                    return (int(mm.group(1)), int(mm.group(2))) if mm else (0, 0)
                e = cmd("eval"); ev = [e[0], e[1], cmd("exists")[0], cmd("get")[0], cmd("set")[0]]
                if cs:
                    open(f"{out}/redis-commandstats.last", "w").write(cs)
                    open(f"{out}/redis-slowlog.last", "w").write(
                        "LEN " + sh("docker", "exec", redis, "redis-cli", "SLOWLOG", "LEN") + sh("docker", "exec", redis, "redis-cli", "SLOWLOG", "GET", "20"))
                    st = sh("docker", "exec", redis, "redis-cli", "INFO", "stats")
                    mo = re.search(r"instantaneous_ops_per_sec:(\d+)", st)
                    ops = mo.group(1) if mo else ""
            load1 = os.getloadavg()[0]
            series.write(f"{time.time():.2f},{g.get('admitted',0):.0f},{g.get('rejected',0):.0f},{g.get('fail_open',0):.0f},"
                         f"{ev[0]},{ev[1]},{ev[2]},{ev[3]},{ev[4]},{ops},{load1:.2f}\n")
        except Exception:
            pass
    d = nxt - time.time()
    if d > 0:
        time.sleep(d)
    else:
        nxt = time.time()
