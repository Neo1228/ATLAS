#!/usr/bin/env python3
"""application 테스트 shard 배정용 가중치를 재생성하고 균형이 얼마나 달라지는지 보여 준다.

    python3 scripts/ci/update-test-shard-weights.py                  # 최근 성공 30회 -> 보고서만 출력(파일 안 씀)
    python3 scripts/ci/update-test-shard-weights.py --runs 30 --write
    python3 scripts/ci/update-test-shard-weights.py --run <id> --run <id>
    python3 scripts/ci/update-test-shard-weights.py --shard-counts 3,4,5,6
    python3 scripts/ci/update-test-shard-weights.py --local         # 로컬 test-results XML 1회분(권장하지 않음)

가중치는 Backend CI 실행 로그의 `CLASSTIME <클래스> <초>` 줄에서 만든다. 클래스별 **평균**을 쓴다
(중앙값은 2~4초와 22~35초 사이를 오가는 클래스의 기대 시간을 낮게 잡는다. docs/03-experiments/EXP-076).
배정은 TestShardFilter(Java)와 같은 LPT라서 이 스크립트의 shard 예측이 실제 배정과 같다.
파일은 `--write`를 줘야 쓰고, 커밋은 사람이 한다. 값은 테스트 코드나 CI 환경이 크게 바뀌면 다시 만든다.
"""
import argparse
import concurrent.futures
import glob
import json
import os
import re
import statistics
import subprocess
import sys
import tempfile

TARGET = "atlas-application/src/test/resources/ci/test-shard-weights.properties"
LOCAL_RESULTS = "atlas-application/build/test-results/test/TEST-*.xml"
CLASSTIME = re.compile(r"CLASSTIME (\S+) ([\d.]+)")
JOB_PREFIX = "Application tests"
HEADER = [
    "# 테스트 shard 배정용 가중치(초). scripts/ci/update-test-shard-weights.py로 생성한다.",
    "# 배정에만 쓰이며 값이 틀려도 테스트가 빠지지 않는다.",
]


# ---------- 배정 (TestShardFilter.java 와 같은 규칙) ----------

def java_hash(text):
    value = 0
    for char in text:
        value = (31 * value + ord(char)) & 0xFFFFFFFF
    return value - (1 << 32) if value >= 1 << 31 else value


def lpt(weights, shards):
    """무거운 클래스부터 가장 가벼운 shard에 넣는다. 동률은 이름순, 가장 앞 shard."""
    loads = [0.0] * shards
    assignment = {}
    for name, seconds in sorted(weights.items(), key=lambda item: (-item[1], item[0])):
        index = min(range(shards), key=lambda i: (loads[i], i))
        assignment[name] = index
        loads[index] += seconds
    return assignment


def shard_sums(assignment, times, shards):
    """실행 하나의 클래스 시간(times)을 배정대로 shard별로 합한다. 가중치에 없는 클래스는 이름 해시."""
    sums = [0.0] * shards
    for name, seconds in times.items():
        index = assignment.get(name)
        if index is None:
            index = java_hash(name) % shards
        sums[index] += seconds
    return sums


def mean_weights(runs):
    seen = {}
    for run in runs:
        for name, seconds in run.items():
            seen.setdefault(name, []).append(seconds)
    return {name: statistics.fmean(values) for name, values in seen.items()}


def median_weights(runs):
    seen = {}
    for run in runs:
        for name, seconds in run.items():
            seen.setdefault(name, []).append(seconds)
    return {name: statistics.median(values) for name, values in seen.items()}


# ---------- 데이터 수집 ----------

def gh(*args):
    return subprocess.run(["gh", *args], capture_output=True, text=True, check=True).stdout


def outer(name):
    return name.split("$")[0]


def fetch_run(run_id, cache_dir):
    """성공한 실행 하나에서 클래스별 시간과 shard job 시간을 읽는다. 쓸 수 없으면 None."""
    path = os.path.join(cache_dir, "%s.json" % run_id)
    if os.path.exists(path):
        with open(path, encoding="utf-8") as handle:
            return json.load(handle)
    jobs = json.loads(gh("api", "repos/{owner}/{repo}/actions/runs/%s/jobs?per_page=100" % run_id))["jobs"]
    app = [job for job in jobs if job["name"].startswith(JOB_PREFIX)]
    if not app or any(job["conclusion"] != "success" for job in app):
        return None
    times = {}
    shard_jobs = []
    for job in app:
        log = gh("api", "--allow-escape-sequences", "repos/{owner}/{repo}/actions/jobs/%s/logs" % job["id"])
        found = {}
        for name, seconds in CLASSTIME.findall(log):
            found[outer(name)] = found.get(outer(name), 0.0) + float(seconds)
        if not found:
            return None
        for name, seconds in found.items():
            times[name] = times.get(name, 0.0) + seconds
        started, completed = job["started_at"], job["completed_at"]
        shard_jobs.append([sum(found.values()), job_seconds(started, completed)])
    result = {"classes": times, "jobs": shard_jobs, "shards": len(app)}
    os.makedirs(cache_dir, exist_ok=True)
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(result, handle)
    return result


def job_seconds(started, completed):
    from datetime import datetime
    parse = lambda text: datetime.strptime(text, "%Y-%m-%dT%H:%M:%SZ")
    return (parse(completed) - parse(started)).total_seconds()


def latest_runs(count, cache_dir):
    listed = json.loads(gh("run", "list", "--workflow", "backend-ci.yml", "--status", "success",
                           "--limit", str(count * 4), "--json", "databaseId"))
    ids = [str(item["databaseId"]) for item in listed]
    collected = []
    with concurrent.futures.ThreadPoolExecutor(8) as pool:
        for start in range(0, len(ids), 8):
            for run_id, run in zip(ids[start:start + 8], pool.map(lambda i: fetch_run(i, cache_dir), ids[start:start + 8])):
                if run is not None:
                    run["id"] = run_id
                    collected.append(run)
            if len(collected) >= count:
                break
    return collected[:count]


def local_run():
    times = {}
    for path in glob.glob(LOCAL_RESULTS):
        with open(path, encoding="utf-8") as handle:
            match = re.search(r'name="([^"]+)" tests="\d+".*?time="([\d.]+)"', handle.read(1200))
        if match:
            times[outer(match.group(1))] = times.get(outer(match.group(1)), 0.0) + float(match.group(2))
    return {"id": "local", "classes": times, "jobs": [], "shards": 0}


def read_weights():
    weights = {}
    if os.path.exists(TARGET):
        with open(TARGET, encoding="utf-8") as handle:
            for line in handle:
                line = line.strip()
                if line and not line.startswith("#"):
                    name, value = line.split("=", 1)
                    weights[name] = float(value)
    return weights


# ---------- 보고 ----------

def spread(assignment, runs, shards):
    """실행마다 (최대 shard 합, 최대/평균)을 구해 평균과 실행 간 표준편차를 돌려준다."""
    maxima, ratios = [], []
    for times in runs:
        sums = shard_sums(assignment, times, shards)
        maxima.append(max(sums))
        ratios.append(max(sums) / (sum(sums) / shards))
    sd = statistics.pstdev(maxima) if len(maxima) > 1 else 0.0
    return statistics.fmean(maxima), statistics.fmean(ratios), sd


def walk_forward(runs, shards, builder):
    """가장 새 실행 3분의 1을 검증용으로 쓴다. 각 검증 실행은 그보다 오래된 실행만으로 만든 가중치로 잰다.

    클래스 시간이 시간에 따라 흘러가므로(EXP-076) 미래 실행을 훈련에 섞는 교차 검증은 낙관적이다.
    runs는 새 실행이 앞이다. builder(older)가 가중치를 돌려준다.
    """
    maxima, ratios = [], []
    for index in range(max(1, len(runs) // 3)):
        older = runs[index + 1:]
        if not older:
            break
        sums = shard_sums(lpt(builder(older), shards), runs[index], shards)
        maxima.append(max(sums))
        ratios.append(max(sums) / (sum(sums) / shards))
    return statistics.fmean(maxima), statistics.fmean(ratios), statistics.pstdev(maxima)


def report(runs, shards, current):
    classes = [run["classes"] for run in runs]
    totals = [sum(times.values()) for times in classes]
    print("수집: 성공 실행 %d회, 클래스 %d개, 실행당 클래스 시간 합 평균 %.0f초(최소 %.0f, 최대 %.0f), shard %d개 기준"
          % (len(runs), len({name for times in classes for name in times}), statistics.fmean(totals),
             min(totals), max(totals), shards))
    if len(runs) < 5:
        print("주의: 실행이 5회 미만이라 아래 검증 수치는 참고용이다.")
    new_weights = mean_weights(classes)
    newest = set(classes[0])
    new_weights = {name: seconds for name, seconds in new_weights.items() if name in newest}

    rows = []
    if current:
        missing = [sum(1 for name in times if name not in current) for times in classes]
        missing_seconds = [sum(s for name, s in times.items() if name not in current) for times in classes]
        print("현재 파일: 클래스 %d개. 실행마다 파일에 없는 클래스 평균 %.1f개(시간 %.0f초, 합의 %.0f%%)는 이름 해시로 배정된다."
              % (len(current), statistics.fmean(missing), statistics.fmean(missing_seconds),
                 100 * statistics.fmean(missing_seconds) / statistics.fmean(totals)))
        rows.append(("현재 파일(그대로)",) + spread(lpt(current, shards), classes, shards))
    validated = len(classes) // 3
    if validated >= 1 and len(classes) > 3:
        rows.append(("직전 1회 LPT(검증)",) + walk_forward(classes, shards, lambda older: older[0]))
        rows.append(("중앙값 LPT(검증)",) + walk_forward(classes, shards, median_weights))
        rows.append(("평균 LPT(검증)",) + walk_forward(classes, shards, mean_weights))
    rows.append(("새 가중치(학습에 쓴 실행)",) + spread(lpt(new_weights, shards), classes, shards))
    print("")
    print("| 방식 | 최대 shard 클래스 시간 합 평균(초) | 최대/평균 | 실행 간 표준편차(초) |")
    print("| --- | ---: | ---: | ---: |")
    for name, maximum, ratio, sd in rows:
        print("| %s | %.0f | %.3f | %.0f |" % (name, maximum, ratio, sd))
    print("\n`(검증)`은 가장 새 %d회를 그보다 오래된 실행으로 만든 가중치로 잰 값이다(미래 실행을 훈련에 쓰지 않는다). 직전 1회가 지금 방식이다. 실행끼리 완전히 독립이 아니다(같은 PR의 재실행)." % max(1, len(classes) // 3))
    print("이상적 균형(합/shard 수) 평균 %.0f초. 최대 shard가 이보다 높은 만큼이 배정으로 줄일 수 있는 상한이다." % (statistics.fmean(totals) / shards))
    return new_weights


def shard_count_model(runs, counts):
    points = [(sum_, seconds) for run in runs for sum_, seconds in run["jobs"]]
    if len(points) < 8:
        print("\nshard 개수 모델: job 시간 표본이 8개 미만이라 건너뛴다.")
        return
    slope, intercept = statistics.linear_regression([p[0] for p in points], [p[1] for p in points])
    classes = [run["classes"] for run in runs]
    total = statistics.fmean(sum(times.values()) for times in classes)
    print("\nshard 개수 모델: job 시간 = %.0f초(shard마다 드는 고정 시간) + %.3f x 클래스 시간 합 (shard job %d개 회귀)" % (intercept, slope, len(points)))
    print("| shard 수 | 최대 shard 클래스 시간 합(검증) | 예상 가장 느린 job(초) | job 합계(분) |")
    print("| ---: | ---: | ---: | ---: |")
    for count in counts:
        maximum, _, _ = walk_forward(classes, count, mean_weights)
        print("| %d | %.0f | %.0f | %.1f |" % (count, maximum, intercept + slope * maximum, (count * intercept + slope * total) / 60))
    print("가장 느린 job은 큐 대기·변경 감지·판정 job을 뺀 값이다. 고정 시간은 클래스 시간 0일 때로 외삽한 값이라 오차가 크다.")


def write_target(weights, runs):
    lines = HEADER + ["# 생성: Backend CI 성공 %d회(run id %s ~ %s)의 클래스별 평균." % (len(runs), runs[-1]["id"], runs[0]["id"])]
    lines += ["%s=%.1f" % (name, max(seconds, 0.1)) for name, seconds in sorted(weights.items())]
    with open(TARGET, "w", encoding="utf-8") as handle:
        handle.write("\n".join(lines) + "\n")
    print("\n%d개 클래스, 합계 %.0f초 -> %s" % (len(weights), sum(weights.values()), TARGET))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    source = parser.add_mutually_exclusive_group()
    source.add_argument("--runs", type=int, default=30, help="최근 성공한 Backend CI 실행 개수(기본 30)")
    source.add_argument("--run", action="append", help="Backend CI 실행 id(여러 번 줄 수 있다)")
    source.add_argument("--local", action="store_true", help="로컬 test-results XML 1회분")
    parser.add_argument("--shards", type=int, default=4, help="shard 개수(기본 4, backend-ci.yml matrix와 같아야 한다)")
    parser.add_argument("--shard-counts", help="shard 개수별 예상 시간을 비교한다(예: 3,4,5,6)")
    parser.add_argument("--write", action="store_true", help="가중치 파일을 쓴다(기본은 보고만)")
    parser.add_argument("--cache", default=os.path.join(tempfile.gettempdir(), "atlas-shard-cache"), help="내려받은 실행 데이터 캐시")
    args = parser.parse_args()

    if args.local:
        runs = [local_run()]
    elif args.run:
        runs = []
        for run_id in sorted(args.run, key=int, reverse=True):  # 새 실행이 앞. 없어진 클래스를 걸러내는 기준이다.
            run = fetch_run(run_id, args.cache)
            if run:
                run["id"] = run_id
                runs.append(run)
    else:
        runs = latest_runs(args.runs, args.cache)
    if not runs or not runs[0]["classes"]:
        sys.exit("가중치로 쓸 실행 결과가 없습니다. --runs N, --run <id> 또는 로컬에서 ./gradlew :atlas-application:test 후 --local을 쓰세요.")

    weights = report(runs, args.shards, read_weights())
    if args.shard_counts:
        shard_count_model(runs, [int(count) for count in args.shard_counts.split(",")])
    if args.write:
        write_target(weights, runs)
    else:
        print("\n파일은 바꾸지 않았다. 반영하려면 --write를 붙인다.")


if __name__ == "__main__":
    main()
