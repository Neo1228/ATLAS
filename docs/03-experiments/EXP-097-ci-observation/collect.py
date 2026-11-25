#!/usr/bin/env python3
"""EXP-097 수집: GitHub Actions 실행 데이터를 읽기 전용(GET)으로 모아 집계 CSV를 만든다.

사용: python3 collect.py [--raw /tmp/ci97/raw] [--out <이 폴더>/data]
필요: gh auth (Neo1228/ATLAS 읽기 권한). 쓰기 요청은 하지 않는다.
원본 로그(zip)는 저장하지 않고 시도마다 파싱한 JSON만 --raw 에 캐시한다(재실행은 캐시를 쓴다).
"""
import argparse
import concurrent.futures as cf
import csv
import gzip
import io
import json
import re
import subprocess
import sys
import zipfile
from datetime import datetime, timezone
from pathlib import Path

REPO = 'Neo1228/ATLAS'
WORKFLOW = 'backend-ci.yml'
WINDOW_START = '2026-11-23T12:11:38Z'  # #502 병합 시각(샤딩과 CLASSTIME 도입)
BUILD_FILE = re.compile(r'(^|/)(build\.gradle(\.kts)?|settings\.gradle(\.kts)?|gradle\.properties|gradle/.*|.*\.gradle(\.kts)?)$')

CLASSTIME = re.compile(r'^\S+Z CLASSTIME (\S+) ([\d.]+)\s*$')
DOWNLOADED = re.compile(r'^\S+Z DOWNLOADED_JARS (\d+)\s*$')
FAILED_LINE = re.compile(r'^\S+Z (\S+) > .*FAILED\s*$')
P429 = re.compile(r'Received status code 429|Too Many Requests')
HTTP_ERR = re.compile(r'Could not (GET|HEAD) |status code [45]\d\d')


def gh(*args, binary=False):
    r = subprocess.run(['gh', 'api', *args], capture_output=True)
    if r.returncode != 0:
        return None
    return r.stdout if binary else r.stdout.decode('utf-8')


def gh_json(path):
    out = gh(path)
    return None if out is None else json.loads(out)


def parse_ts(s):
    return datetime.strptime(s, '%Y-%m-%dT%H:%M:%SZ').replace(tzinfo=timezone.utc)


def job_kind(name):
    if name.startswith('Application tests'):
        return 'app'
    if name.startswith('Static checks'):
        return 'verify'
    if name.startswith('Java 25'):
        return 'backend'
    return 'changes'


def parse_log_zip(raw):
    """job 로그 파일(압축 파일 최상위 `<번호>_<job 이름>.txt`)마다 필요한 줄만 뽑는다."""
    out = {}
    z = zipfile.ZipFile(io.BytesIO(raw))
    for name in z.namelist():
        if '/' in name or not name.endswith('.txt'):
            continue
        job = re.sub(r'^\d+_', '', name[:-4])
        shard = re.search(r'\((\d)_4\)', job)
        text = z.read(name).decode('utf-8', 'replace')
        classtimes, jars, n429, nhttp, failed = [], None, 0, 0, []
        for line in text.split('\n'):
            m = CLASSTIME.match(line)
            if m:
                classtimes.append([m.group(1), float(m.group(2))])
                continue
            m = DOWNLOADED.match(line)
            if m:
                jars = int(m.group(1))
                continue
            if P429.search(line):
                n429 += 1
            if HTTP_ERR.search(line):
                nhttp += 1
            m = FAILED_LINE.match(line)
            if m and 'Task :' not in line:
                failed.append(m.group(1))
        out[(int(shard.group(1)) if shard else 0, job_kind(job))] = {
            'job': job, 'classtimes': classtimes, 'jars': jars, 'n429': n429,
            'nhttp': nhttp, 'failed_classes': sorted(set(failed)),
        }
    return out


def fetch_attempt(rid, att, raw_dir):
    cache = raw_dir / f'{rid}_{att}.json'
    if cache.exists():
        return json.loads(cache.read_text())
    meta = gh_json(f'repos/{REPO}/actions/runs/{rid}/attempts/{att}')
    jobs = gh_json(f'repos/{REPO}/actions/runs/{rid}/attempts/{att}/jobs?per_page=100')
    if meta is None or jobs is None:
        return None
    z = gh(f'repos/{REPO}/actions/runs/{rid}/attempts/{att}/logs', binary=True)
    logs = parse_log_zip(z) if z else None
    rec = {
        'run_id': rid, 'attempt': att, 'event': meta['event'], 'status': meta['status'],
        'conclusion': meta['conclusion'], 'started': meta['run_started_at'],
        'head_sha': meta['head_sha'], 'head_branch': meta['head_branch'],
        'pr': (meta.get('pull_requests') or [{}])[0].get('number'),
        'logs_ok': logs is not None, 'jobs': [],
    }
    for j in jobs['jobs']:
        shard = re.search(r'\((\d)/4\)', j['name'])
        key = (int(shard.group(1)) if shard else 0, job_kind(j['name']))
        lg = (logs or {}).get(key, {})
        rec['jobs'].append({
            'name': j['name'], 'kind': key[1], 'shard': key[0], 'conclusion': j['conclusion'],
            'started': j['started_at'], 'completed': j['completed_at'],
            'jars': lg.get('jars'), 'n429': lg.get('n429'), 'nhttp': lg.get('nhttp'),
            'failed_classes': lg.get('failed_classes'), 'classtimes': lg.get('classtimes'),
            'log': bool(lg),
        })
    if rec['status'] == 'completed':
        cache.write_text(json.dumps(rec))
    return rec


def list_runs():
    out = gh('--paginate', f'repos/{REPO}/actions/workflows/{WORKFLOW}/runs?per_page=100&created=>=2026-11-23T11:00:00Z',
             '--jq', '.workflow_runs[] | [.id,.run_attempt,.status] | @tsv')
    return [l.split('\t') for l in out.strip().split('\n') if l]


def list_prs():
    """PR 번호·head 브랜치·최종 head SHA·병합 정보(2026-11-20 이후 만든 PR). 시도의 PR 번호는 이 표로 채운다."""
    out = gh('--paginate', f'repos/{REPO}/pulls?state=all&per_page=100&sort=created&direction=desc', '--jq',
             '.[] | [.number,.head.ref,.head.sha,(.merged_at // ""),(.merge_commit_sha // ""),.created_at,.state] | @tsv')
    rows = [dict(zip(('pr', 'branch', 'head_sha', 'merged_at', 'merge_sha', 'created_at', 'state'), l.split('\t')))
            for l in out.strip().split('\n') if l]
    return [r for r in rows if r['created_at'] >= '2026-11-20T00:00:00Z']


def secs(a, b):
    return (parse_ts(b) - parse_ts(a)).total_seconds() if a and b else None


def write_csv(path, rows, fields):
    with open(path, 'w', newline='', encoding='utf-8') as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        w.writerows(rows)


def main():
    ap = argparse.ArgumentParser()
    here = Path(__file__).resolve().parent
    ap.add_argument('--raw', default='/tmp/ci97/raw')
    ap.add_argument('--out', default=str(here / 'data'))
    args = ap.parse_args()
    raw_dir, out = Path(args.raw), Path(args.out)
    raw_dir.mkdir(parents=True, exist_ok=True)
    out.mkdir(parents=True, exist_ok=True)
    t_cut = datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')

    runs = list_runs()
    todo = [(rid, a) for rid, att, st in runs if st == 'completed' for a in range(1, int(att) + 1)]
    print(f'실행 {len(runs)}건, 시도 {len(todo)}건 받는다', file=sys.stderr)
    with cf.ThreadPoolExecutor(6) as ex:
        recs = list(ex.map(lambda t: fetch_attempt(t[0], t[1], raw_dir), todo))
    recs = [r for r in recs if r and r['status'] == 'completed' and r['started'] >= WINDOW_START]
    missing = sum(1 for r in recs if not r['logs_ok'])

    prs = list_prs()
    write_csv(out / 'prs.csv', prs, ['pr', 'branch', 'head_sha', 'merged_at', 'merge_sha', 'created_at', 'state'])
    by_branch = {}
    for p in sorted(prs, key=lambda x: x['created_at']):  # 같은 브랜치 이름을 다시 쓴 경우 가장 늦게 만든 PR
        by_branch[p['branch']] = p
    for r in recs:
        p = by_branch.get(r['head_branch']) if r['event'] == 'pull_request' else None
        r['pr'] = int(p['pr']) if p else r['pr']
        r['final_head'] = int(bool(p and p['merged_at'] and p['head_sha'] == r['head_sha']))
        r['branch'] = r['head_branch']

    attempts, jobs_rows, class_rows = [], [], []
    for r in recs:
        ran = [j for j in r['jobs'] if j['conclusion'] not in ('skipped', 'cancelled', None)]
        total = sum(secs(j['started'], j['completed']) or 0 for j in ran)
        ceil_min = sum(-(-int(secs(j['started'], j['completed']) or 0) // 60) for j in ran)
        kinds = {j['kind']: j['conclusion'] for j in r['jobs'] if j['kind'] != 'app'}
        apps = [j for j in r['jobs'] if j['kind'] == 'app']
        attempts.append({
            'run_id': r['run_id'], 'attempt': r['attempt'], 'event': r['event'], 'pr': r['pr'] or '',
            'head_sha': r['head_sha'][:10], 'branch': r['branch'], 'final_head': r['final_head'], 'started': r['started'], 'conclusion': r['conclusion'],
            'verify': kinds.get('verify'), 'backend': kinds.get('backend'),
            'app_jobs_ran': sum(1 for j in apps if j['conclusion'] not in ('skipped', 'cancelled', None)),
            'app_jobs_total': len(apps), 'job_seconds': int(total), 'job_minutes_ceil': ceil_min,
            'logs_ok': int(r['logs_ok']),
        })
        for j in r['jobs']:
            ct = j['classtimes'] or []
            loc = [c for c in ct if c[0].endswith('.LocationFaultConvergenceIntegrationTest')]
            failed = j['failed_classes'] or []
            jobs_rows.append({
                'run_id': r['run_id'], 'attempt': r['attempt'], 'event': r['event'], 'pr': r['pr'] or '', 'final_head': r['final_head'],
                'started_attempt': r['started'], 'kind': j['kind'], 'shard': j['shard'],
                'conclusion': j['conclusion'], 'job_started': j['started'], 'job_completed': j['completed'],
                'seconds': secs(j['started'], j['completed']) or '', 'log': int(j['log']),
                'downloaded_jars': '' if j['jars'] is None else j['jars'],
                'n429': '' if j['n429'] is None else j['n429'],
                'nhttp': '' if j['nhttp'] is None else j['nhttp'],
                'classtime_sum': round(sum(c[1] for c in ct), 1) if ct else '',
                'loc_ran': int(bool(loc)), 'loc_seconds': loc[0][1] if loc else '',
                'loc_failed': int(any(c.endswith('LocationFaultConvergenceIntegrationTest') for c in failed)),
                'failed_classes': ';'.join(failed),
            })
            if j['kind'] == 'app' and j['conclusion'] == 'success':
                for name, s in ct:
                    class_rows.append((r['run_id'], r['attempt'], j['shard'], name, s))

    write_csv(out / 'attempts.csv', attempts, list(attempts[0]))
    write_csv(out / 'jobs.csv', jobs_rows, list(jobs_rows[0]))
    with gzip.open(out / 'classtime.csv.gz', 'wt', encoding='utf-8') as f:
        f.write('run_id,attempt,shard,class,seconds\n')
        for row in class_rows:
            f.write(','.join(str(x) for x in row) + '\n')

    # 따뜻한 시도에서 0이 아닌 DOWNLOADED_JARS: PR이 빌드 파일을 바꿨는지 확인(설명 가능 여부)
    nonzero = {}
    for row in jobs_rows:
        if row['downloaded_jars'] not in ('', 0) and row['pr']:
            nonzero.setdefault(row['pr'], set()).add(row['run_id'])
    pr_rows = []
    for pr in sorted(nonzero):
        files = gh('--paginate', f'repos/{REPO}/pulls/{pr}/files?per_page=100', '--jq', '.[].filename')
        names = files.split() if files else []
        pr_rows.append({'pr': pr, 'files': len(names), 'build_files': ';'.join(n for n in names if BUILD_FILE.search(n))})
    write_csv(out / 'nonzero_jars_prs.csv', pr_rows, ['pr', 'files', 'build_files']) if pr_rows else None

    # Q4: 저장소 설정과 병합 PR의 base 최신성
    repo = gh_json(f'repos/{REPO}') or {}
    settings = {k: repo.get(k) for k in ('allow_squash_merge', 'allow_merge_commit', 'allow_rebase_merge', 'default_branch')}
    prot = gh_json(f'repos/{REPO}/branches/main/protection')
    rules = gh_json(f'repos/{REPO}/rules/branches/main')
    commits = json.loads(gh('--paginate', f'repos/{REPO}/commits?sha=main&since={WINDOW_START}&per_page=100', '--jq',
                           '[.[] | {sha, parents: (.parents|length), date: .commit.committer.date, msg: (.commit.message|split("\\n")[0]), parent: .parents[0].sha}]').replace('][', ','))
    merged = []
    by_merge = {p['merge_sha']: p for p in prs if p['merge_sha']}
    for c in commits:
        p = by_merge.get(c['sha'])
        if p is None:
            hit = gh_json(f'repos/{REPO}/commits/{c["sha"]}/pulls')
            p = {'pr': hit[0]['number'], 'head_sha': hit[0]['head']['sha']} if hit else None
        cmp_ = gh_json(f'repos/{REPO}/compare/{c["parent"]}...{p["head_sha"]}') if p else None
        merged.append({**c, 'pr': p['pr'] if p else '', 'behind_by': cmp_['behind_by'] if cmp_ else '',
                       'ahead_by': cmp_['ahead_by'] if cmp_ else ''})
    write_csv(out / 'main_commits.csv', [{k: v for k, v in c.items() if k != 'msg'} for c in merged],
              ['sha', 'date', 'parents', 'parent', 'pr', 'behind_by', 'ahead_by'])
    meta = {
        't_cut': t_cut, 'runs_listed': len(runs), 'attempts_fetched': len(recs), 'logs_missing': missing,
        'repo_settings': settings,
        'branch_protection': (None if prot is None else {
            'strict': (prot.get('required_status_checks') or {}).get('strict'),
            'contexts': (prot.get('required_status_checks') or {}).get('contexts')}),
        'branch_rules_count': None if rules is None else len(rules),
        'branch_rules_types': None if rules is None else sorted({x['type'] for x in rules}),
        'branch_rules_strict': None if rules is None else [
            (x.get('parameters') or {}).get('strict_required_status_checks_policy') for x in rules if x['type'] == 'required_status_checks'],
    }
    (out / 'collection.json').write_text(json.dumps(meta, indent=2, ensure_ascii=False))
    print(json.dumps(meta, ensure_ascii=False), file=sys.stderr)


if __name__ == '__main__':
    main()
