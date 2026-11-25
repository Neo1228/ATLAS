#!/usr/bin/env python3
"""EXP-097 분석: collect.py 가 만든 집계 CSV 로 사전 등록한 지표를 계산해 results.md/results.json 을 쓴다.

표준 라이브러리만 쓴다(scipy 없음). 사용: python3 analyze.py [--data <폴더>] [--repo <git 작업 폴더>]
"""
import argparse
import csv
import gzip
import json
import math
import random
import statistics
import subprocess
from collections import defaultdict
from datetime import datetime, timedelta, timezone
from pathlib import Path

B = {  # 실제 병합 시각(UTC), gh pr view 로 확인
    'START': '2026-11-23T12:11:38Z', 'B604': '2026-11-24T12:36:49Z',
    'B609': '2026-11-24T13:44:00Z', 'B613': '2026-11-24T15:04:43Z',
}
GUARD = timedelta(minutes=30)
LOC = 'com.atlas.order.LocationFaultConvergenceIntegrationTest'
KNOWN_429 = ['45231283583', '45330809510', '45344744453']
KNOWN_LOC = ['45181797918', '45231697943', '45342680146']
random.seed(97)
OUT = []


def ts(s):
    return datetime.strptime(s, '%Y-%m-%dT%H:%M:%SZ').replace(tzinfo=timezone.utc)


def period(started):
    t = ts(started)
    if t < ts(B['B604']):
        return 'S0'
    if t < ts(B['B609']):
        return 'S1'
    if t < ts(B['B613']):
        return 'S2'
    return 'S3'


ORDER = {'S0': 0, 'S1': 1, 'S2': 2, 'S3': 3}
OWN = {'604': 'S1', '609': 'S2', '613': 'S3'}  # 이 PR의 최종 head 에서 도는 CI는 병합 전이어도 그 변경을 이미 담고 있다


def code_period(a):
    """사후 보정: 변경을 담은 PR 자신의 실행은 병합 시각 이전이어도 변경 뒤 기간으로 센다."""
    p = period(a['started'])
    own = OWN.get(a['pr']) if a['event'] == 'pull_request' and a['final_head'] == '1' else None
    return own if own and ORDER[own] > ORDER[p] else p


def near_boundary(started, names):
    t = ts(started)
    return any(ts(B[n]) <= t < ts(B[n]) + GUARD for n in names)


def emit(s=''):
    OUT.append(s)


def table(header, rows):
    emit('| ' + ' | '.join(header) + ' |')
    emit('| ' + ' | '.join('---' for _ in header) + ' |')
    for r in rows:
        emit('| ' + ' | '.join(str(x) for x in r) + ' |')
    emit()


def q(values, p):
    v = sorted(values)
    if not v:
        return float('nan')
    k = (len(v) - 1) * p
    lo, hi = math.floor(k), math.ceil(k)
    return v[lo] + (v[hi] - v[lo]) * (k - lo)


def dist(values):
    if not values:
        return ['0', '', '', '', '', '']
    return [len(values), f'{min(values):.1f}', f'{q(values, .25):.1f}/{q(values, .5):.1f}/{q(values, .75):.1f}',
            f'{q(values, .9):.1f}', f'{max(values):.1f}', '']


def binom_cdf(k, n, p):
    if p <= 0:
        return 1.0
    if p >= 1:
        return 1.0 if k >= n else 0.0
    total = 0.0
    for i in range(0, k + 1):
        total += math.exp(math.lgamma(n + 1) - math.lgamma(i + 1) - math.lgamma(n - i + 1)
                          + i * math.log(p) + (n - i) * math.log1p(-p))
    return min(1.0, total)


def cp(k, n, alpha=0.05):
    """양쪽 Clopper-Pearson 정확 구간."""
    if n == 0:
        return (float('nan'), float('nan'))

    def solve(f, lo=0.0, hi=1.0):
        for _ in range(80):
            mid = (lo + hi) / 2
            lo, hi = (mid, hi) if f(mid) else (lo, mid)
        return (lo + hi) / 2
    lower = 0.0 if k == 0 else solve(lambda p: 1 - binom_cdf(k - 1, n, p) < alpha / 2)
    upper = 1.0 if k == n else solve(lambda p: binom_cdf(k, n, p) > alpha / 2)
    return lower, upper


def ci_text(k, n):
    if n == 0:
        return f'{k}/{n}'
    lo, hi = cp(k, n)
    return f'{k}/{n} = {100 * k / n:.2f}% [{100 * lo:.2f}, {100 * hi:.2f}]'


def overlap(a, b):
    return not (a[1] < b[0] or b[1] < a[0])


def mannwhitney(a, b):
    """양측 Mann-Whitney U 정규 근사(동점 보정, 연속성 보정)."""
    na, nb = len(a), len(b)
    if na < 2 or nb < 2:
        return float('nan')
    allv = sorted([(x, 0) for x in a] + [(x, 1) for x in b])
    ranks, i = {}, 0
    tie = 0.0
    while i < len(allv):
        j = i
        while j < len(allv) and allv[j][0] == allv[i][0]:
            j += 1
        r = (i + j + 1) / 2
        t = j - i
        tie += t ** 3 - t
        for k in range(i, j):
            ranks.setdefault(allv[k][0], r)
        i = j
    ra = sum(ranks[x] for x in a)
    u = ra - na * (na + 1) / 2
    n = na + nb
    mu = na * nb / 2
    var = na * nb / 12 * ((n + 1) - tie / (n * (n - 1)))
    if var <= 0:
        return 1.0
    z = (abs(u - mu) - 0.5) / math.sqrt(var)
    return math.erfc(z / math.sqrt(2))


def wilcoxon(d):
    d = [x for x in d if x != 0]
    n = len(d)
    if n < 6:
        return float('nan')
    order = sorted(range(n), key=lambda i: abs(d[i]))
    ranks = [0.0] * n
    i = 0
    tie = 0.0
    while i < n:
        j = i
        while j < n and abs(d[order[j]]) == abs(d[order[i]]):
            j += 1
        for k in range(i, j):
            ranks[order[k]] = (i + j + 1) / 2
        tie += (j - i) ** 3 - (j - i)
        i = j
    w = sum(r for r, x in zip(ranks, d) if x > 0)
    mu = n * (n + 1) / 4
    var = n * (n + 1) * (2 * n + 1) / 24 - tie / 48
    z = (abs(w - mu) - 0.5) / math.sqrt(var)
    return math.erfc(z / math.sqrt(2))


def fisher(a, b, c, d):
    """2x2 양측 Fisher 정확 검정(확률이 관측 이하인 표를 모두 더함)."""
    n = a + b + c + d
    r1, c1 = a + b, a + c

    def pr(x):
        return math.comb(c1, x) * math.comb(n - c1, r1 - x) / math.comb(n, r1)
    obs = pr(a)
    return min(1.0, sum(pr(x) for x in range(max(0, r1 - (n - c1)), min(r1, c1) + 1) if pr(x) <= obs * (1 + 1e-9)))


def cluster_boot_diff(ga, gb, reps=2000):
    """ga, gb: {군집: [값]}. 후-전 중앙값 차이의 군집 부트스트랩 95% 구간."""
    ka, kb = list(ga), list(gb)
    if not ka or not kb:
        return (float('nan'), float('nan'))
    diffs = []
    for _ in range(reps):
        va = [x for k in random.choices(ka, k=len(ka)) for x in ga[k]]
        vb = [x for k in random.choices(kb, k=len(kb)) for x in gb[k]]
        diffs.append(statistics.median(va) - statistics.median(vb))
    return q(diffs, .025), q(diffs, .975)


def hl_shift(a, b):
    """Hodges-Lehmann 이동량: 모든 쌍 차이의 중앙값(a - b)."""
    if not a or not b:
        return float('nan')
    return statistics.median(x - y for x in a for y in b)


def read_csv(path):
    with open(path, encoding='utf-8') as f:
        return list(csv.DictReader(f))


def num(x):
    return float(x) if x not in ('', None) else None


def main():
    ap = argparse.ArgumentParser()
    here = Path(__file__).resolve().parent
    ap.add_argument('--data', default=str(here / 'data'))
    ap.add_argument('--repo', default=str(here.parents[2]))
    args = ap.parse_args()
    data = Path(args.data)
    res = {}
    att = read_csv(data / 'attempts.csv')
    jobs = read_csv(data / 'jobs.csv')
    meta = json.loads((data / 'collection.json').read_text())
    commits = read_csv(data / 'main_commits.csv')
    for a in att:
        a['period'] = period(a['started'])
        a['cperiod'] = code_period(a)
        a['key'] = (a['run_id'], a['attempt'])
    amap = {a['key']: a for a in att}
    for j in jobs:
        j['period'] = period(j['started_attempt'])
        j['key'] = (j['run_id'], j['attempt'])
        j['cperiod'] = amap[j['key']]['cperiod']
        j['final_head'] = amap[j['key']]['final_head']
        j['cluster'] = j['pr'] or j['run_id']

    def kind_of(a):
        if a['conclusion'] == 'cancelled':
            return 'cancelled'
        if a['verify'] == 'skipped':
            return 'docs'
        if int(a['app_jobs_ran']) > 0:
            return 'full'
        if a['event'] == 'push' and a['verify'] in ('success', 'failure'):
            return 'verify_only'
        return 'other'
    for a in att:
        a['kind'] = kind_of(a)

    emit('# EXP-097 분석 결과(자동 생성)')
    emit()
    emit(f'수집 시각 `T_cut` = {meta["t_cut"]}, 나열한 실행 {meta["runs_listed"]}건, 창 안의 시도 {meta["attempts_fetched"]}건, 로그 없음 {meta["logs_missing"]}건.')
    emit()

    # ---- A. 인벤토리
    emit('## A. 인벤토리(기간 × 이벤트, 시도 단위)')
    emit()
    rows = []
    for p in ('S0', 'S1', 'S2', 'S3'):
        for ev in ('pull_request', 'push'):
            s = [a for a in att if a['period'] == p and a['event'] == ev]
            c = defaultdict(int)
            for a in s:
                c[a['kind']] += 1
            rows.append([p, ev, len(s), c['cancelled'], c['docs'], c['full'], c['verify_only'], c['other'],
                         sum(1 for a in s if int(a['attempt']) > 1), sum(1 for a in s if not int(a['logs_ok']))])
    table(['기간', '이벤트', '시도', '취소', '문서 전용', '전체 검사', 'verify 전용', '기타', '재실행 시도(attempt>1)', '로그 없음'], rows)
    span = {p: None for p in ('S0', 'S1', 'S2', 'S3')}
    bounds = [ts(B['START']), ts(B['B604']), ts(B['B609']), ts(B['B613']), ts(meta['t_cut'])]
    emit('기간 길이(시간): ' + ', '.join(f'{p}={(bounds[i + 1] - bounds[i]).total_seconds() / 3600:.1f}' for i, p in enumerate(span)))
    emit()

    # ---- B. 음성 대조
    emit('## B. 음성 대조(탐지기가 반드시 반응해야 하는 사례)')
    emit()
    ctrl = []
    for rid in KNOWN_429:
        hit = [j for j in jobs if j['run_id'] == rid and j['conclusion'] == 'failure' and num(j['n429']) and num(j['n429']) > 0]
        ctrl.append(['(a) 알려진 429 실행', rid, '탐지' if hit else '**놓침**', ';'.join(f'{j["kind"]}{j["shard"]}(attempt {j["attempt"]})' for j in hit)])
    for rid in KNOWN_LOC:
        hit = [j for j in jobs if j['run_id'] == rid and j['loc_failed'] == '1']
        ctrl.append(['(b) 알려진 LocationFault 실패 실행', rid, '탐지' if hit else '**놓침**', ';'.join(f'shard{j["shard"]}(attempt {j["attempt"]})' for j in hit)])
    res['negative_controls_missed'] = [r[1] for r in ctrl if '놓침' in r[2]]
    # (c) 캐시가 없던 시도에서 DOWNLOADED_JARS 5가 보이는가
    first = [j for j in jobs if j['kind'] == 'app' and j['downloaded_jars'] != '' and j['period'] != 'S0']
    five = [j for j in first if num(j['downloaded_jars']) >= 5]
    ctrl.append(['(c) DOWNLOADED_JARS ≥ 5가 B604 뒤에 보임', f'{len(five)}개 job', '탐지' if five else '**놓침**',
                 ';'.join(sorted({j['run_id'] for j in five}))[:120]])
    table(['대조', '대상', '결과', '탐지한 job'], ctrl)

    # ---- C. 실패 분류
    emit('## C. 실패한 job의 분류(모든 시도, 취소 제외)')
    emit()
    cls = defaultdict(lambda: defaultdict(int))
    for j in jobs:
        a = amap[j['key']]
        if a['kind'] == 'cancelled' or j['conclusion'] != 'failure' or j['kind'] not in ('app', 'verify'):
            continue
        if num(j['n429']) and num(j['n429']) > 0:
            c = '429'
        elif j['loc_failed'] == '1':
            c = 'LocationFault 테스트'
        elif j['failed_classes']:
            c = '다른 테스트 클래스 실패'
        else:
            c = '분류 없음(로그에 FAILED 줄 없음)'
        cls[j['period']][c] += 1
    cats = ['429', 'LocationFault 테스트', '다른 테스트 클래스 실패', '분류 없음(로그에 FAILED 줄 없음)']
    table(['기간'] + cats, [[p] + [cls[p][c] for c in cats] for p in ('S0', 'S1', 'S2', 'S3')])
    others = [(j['run_id'], j['attempt'], j['kind'], j['shard'], j['failed_classes'][:60]) for j in jobs
              if j['conclusion'] == 'failure' and amap[j['key']]['kind'] != 'cancelled' and j['kind'] in ('app', 'verify')
              and not (num(j['n429']) or 0) and j['loc_failed'] != '1' and j['period'] in ('S2', 'S3')]
    emit('S2·S3의 429·LocationFault 이외 실패 job: ' + (', '.join(f'run {r}/{a} {k}{s} [{f}]' for r, a, k, s, f in others) or '없음'))
    emit()

    # ---- D. Q1
    emit('## D. Q1: LocationFaultConvergenceIntegrationTest 실패율(클래스 실행 단위)')
    emit()
    ran = [j for j in jobs if j['kind'] == 'app' and j['loc_ran'] == '1' and amap[j['key']]['kind'] != 'cancelled']
    rows = []
    for p in ('S0', 'S1', 'S2', 'S3'):
        s = [j for j in ran if j['period'] == p]
        f = [j for j in s if j['loc_failed'] == '1']
        rows.append([p, len(s), len(f), ci_text(len(f), len(s)), len({j['cluster'] for j in s}), len({j['cluster'] for j in f})])
    table(['기간', '클래스 실행', '실패', '실패율(정확 95% 구간)', '군집(PR) 수', '실패 군집 수'], rows)
    for label, bef, aft, excl in (('주(S0+S1 대 S2+S3)', ('S0', 'S1'), ('S2', 'S3'), ()),
                                  ('민감도(경계 뒤 30분 제외)', ('S0', 'S1'), ('S2', 'S3'), ('B604', 'B609', 'B613'))):
        sb = [j for j in ran if j['period'] in bef and not near_boundary(j['started_attempt'], excl)]
        sa = [j for j in ran if j['period'] in aft and not near_boundary(j['started_attempt'], excl)]
        kb = sum(j['loc_failed'] == '1' for j in sb)
        ka = sum(j['loc_failed'] == '1' for j in sa)
        cb, ca = cp(kb, len(sb)), cp(ka, len(sa))
        verdict = '구간이 겹침: 데이터가 구별하지 못함' if overlap(cb, ca) else '구간이 겹치지 않음: 전후 차이 있음'
        emit(f'- {label}: 전 {ci_text(kb, len(sb))}, 후 {ci_text(ka, len(sa))} → {verdict}. 전 비율로 후 {len(sa)}번 중 0건일 확률 = {(1 - kb / max(len(sb), 1)) ** len(sa):.4f}, 후 0건이면 95% 상한(규칙 3) ≈ {100 * 3 / max(len(sa), 1):.2f}%')
        res.setdefault('q1', {})[label] = {'before': [kb, len(sb)], 'after': [ka, len(sa)], 'cp_before': cb, 'cp_after': ca}
    sb = [j for j in ran if j['cperiod'] in ('S0', 'S1')]
    sa = [j for j in ran if j['cperiod'] in ('S2', 'S3')]
    kb, ka = sum(j['loc_failed'] == '1' for j in sb), sum(j['loc_failed'] == '1' for j in sa)
    emit(f'- 사후 보정(변경을 담은 PR 자신의 실행을 변경 뒤로 셈): 전 {ci_text(kb, len(sb))}, 후 {ci_text(ka, len(sa))} → ' + ('구간이 겹침' if overlap(cp(kb, len(sb)), cp(ka, len(sa))) else '구간이 겹치지 않음'))
    res['q1']['corrected'] = {'before': [kb, len(sb)], 'after': [ka, len(sa)]}
    # 같은 head 의 재실행이 얼마나 있는가(독립이 아님)
    emit(f'- 후 기간 클래스 실행 {len(sa)}개 중 서로 다른 head SHA {len({amap[j["key"]]["head_sha"] for j in sa})}개, 전 기간 {len(sb)}개 중 {len({amap[j["key"]]["head_sha"] for j in sb})}개.')
    # 실패한 job의 분 단위 위치
    emit()
    emit('LocationFault 실패 job: ' + (', '.join(f'run {j["run_id"]}/{j["attempt"]} shard{j["shard"]} {j["started_attempt"]} {j["period"]} {j["event"]} {("PR#" + j["pr"]) if j["pr"] else ""}'
                                              for j in ran if j['loc_failed'] == '1') or '없음'))
    emit()

    # ---- E. Q2 shard 시간
    emit('## E. Q2: shard job 시간(PR 이벤트, 결론 success), 초')
    emit()
    app = [j for j in jobs if j['kind'] == 'app' and j['event'] == 'pull_request' and j['conclusion'] == 'success' and j['seconds'] != '']

    def vals(rows):
        return [float(j['seconds']) for j in rows]

    def grp(rows):
        g = defaultdict(list)
        for j in rows:
            g[j['cluster']].append(float(j['seconds']))
        return g
    rows = []
    for p in ('S0', 'S1', 'S2', 'S3'):
        for sh in (0, 1, 2, 3, 4):
            s = [j for j in app if j['period'] == p and (sh == 0 or int(j['shard']) == sh)]
            rows.append([p, '전체' if sh == 0 else sh] + dist(vals(s))[:5])
    table(['기간', 'shard', 'n', '최소', 'Q1/중앙/Q3', 'p90', '최대'], rows)
    emit('shard 간 편차 지표: 기간별 shard 중앙값의 최대/평균')
    emit()
    rows = []
    for p in ('S0', 'S1', 'S2', 'S3'):
        meds = []
        for sh in (1, 2, 3, 4):
            s = [float(j['seconds']) for j in app if j['period'] == p and int(j['shard']) == sh]
            meds.append(statistics.median(s) if s else float('nan'))
        rows.append([p] + [f'{m:.0f}' for m in meds] + [f'{max(meds) / (sum(meds) / 4):.3f}'])
    table(['기간', 'shard1', 'shard2', 'shard3', 'shard4', '최대/평균'], rows)

    def compare(label, pa, pb, excl=()):
        sa = [j for j in app if j['period'] in pa and not near_boundary(j['started_attempt'], excl)]
        sb = [j for j in app if j['period'] in pb and not near_boundary(j['started_attempt'], excl)]
        va, vb = vals(sa), vals(sb)
        if not va or not vb:
            return [label, len(vb), len(va), '', '', '', '']
        lo, hi = cluster_boot_diff(grp(sa), grp(sb))
        return [label, len(vb), len(va), f'{statistics.median(vb):.0f} → {statistics.median(va):.0f}',
                f'{hl_shift(va, vb):+.1f}', f'[{lo:+.0f}, {hi:+.0f}]', f'{mannwhitney(va, vb):.3g}']
    emit('후 대 전 비교(양수 = 후가 느림). 군집 부트스트랩은 PR 단위.')
    emit()
    table(['비교', 'n 전', 'n 후', '중앙값 전→후', 'HL 이동', '중앙값 차이 95% 구간', 'MWU p'], [
        compare('주: S2+S3 대 S0+S1 (#609)', ('S2', 'S3'), ('S0', 'S1')),
        compare('민감도: 경계 뒤 30분 제외', ('S2', 'S3'), ('S0', 'S1'), ('B604', 'B609', 'B613')),
        compare('영 대조: S3 대 S2 (#613은 PR에 영향 없음)', ('S3',), ('S2',)),
        compare('S1 대 S0 (#604, 시간 영향 주장 없음)', ('S1',), ('S0',)),
    ])

    # ---- F. 짝지은 클래스 비교
    emit('## F. 같은 클래스끼리 짝지은 비교(CLASSTIME, PR 이벤트, 성공한 shard job)')
    emit()
    pmap = {}
    for a in att:
        if a['event'] == 'pull_request':
            pmap[a['key']] = a
    byclass = defaultdict(lambda: defaultdict(list))  # 클래스 → 기간 → 값
    odd = defaultdict(lambda: defaultdict(list))
    with gzip.open(data / 'classtime.csv.gz', 'rt', encoding='utf-8') as f:
        next(f)
        for line in f:
            rid, at, sh, name, s = line.rstrip('\n').split(',')
            a = pmap.get((rid, at))
            if not a or a['kind'] == 'cancelled':
                continue
            byclass[name][a['period']].append(float(s))
            if a['period'] == 'S0':
                odd[name]['odd' if int(rid) % 2 else 'even'].append(float(s))
    touched = touched_classes(args.repo)

    def paired(before, after, source, mult=1.0, skip=(), reps=2000):
        lr, names = [], []
        for name, d in source.items():
            vb = [x for p in before for x in d.get(p, [])]
            va = [x * mult for p in after for x in d.get(p, [])]
            if len(vb) < 3 or len(va) < 3 or name.rsplit('.', 1)[-1] in skip:
                continue
            mb, ma = statistics.median(vb), statistics.median(va)
            if max(mb, ma) < 1.0:
                continue
            lr.append(math.log(max(ma, 0.05) / max(mb, 0.05)))
            names.append((name, mb, ma))
        if len(lr) < 6:
            return None
        boots = [statistics.median(random.choices(lr, k=len(lr))) for _ in range(reps)]
        return {'n': len(lr), 'median_lr': statistics.median(lr), 'ci': (q(boots, .025), q(boots, .975)),
                'p': wilcoxon(lr), 'sum_before': sum(m[1] for m in names), 'sum_after': sum(m[2] for m in names), 'names': names}

    def fmt(r):
        if not r:
            return ['표본 부족', '', '', '', '']
        pct = (math.exp(r['median_lr']) - 1) * 100
        lo, hi = ((math.exp(c) - 1) * 100 for c in r['ci'])
        return [r['n'], f'{pct:+.1f}%', f'[{lo:+.1f}%, {hi:+.1f}%]', f'{r["p"]:.3g}',
                f'{r["sum_before"]:.0f} → {r["sum_after"]:.0f} ({100 * (r["sum_after"] / r["sum_before"] - 1):+.1f}%)']
    main_r = paired(('S0', 'S1'), ('S2', 'S3'), byclass)
    sens_r = paired(('S0', 'S1'), ('S2', 'S3'), byclass, skip=touched)
    place_r = paired(('odd',), ('even',), odd)
    pos_r = paired(('S0', 'S1'), ('S2', 'S3'), byclass, mult=0.85)
    emit(f'테스트 파일을 창 안에서 수정한 클래스(민감도에서 뺌): {len(touched)}개')
    emit()
    table(['비교', '클래스 수', '클래스 중앙값 비율 변화(중앙값)', '95% 구간(클래스 부트스트랩)', 'Wilcoxon p', '같은 클래스 집합의 중앙값 합 전→후(초)'], [
        ['주(S0+S1 대 S2+S3)'] + fmt(main_r),
        ['민감도(수정한 클래스 제외)'] + fmt(sens_r),
        ['위약 대조(S0 run_id 홀수 대 짝수)'] + fmt(place_r),
        ['양성 대조(후 × 0.85 합성)'] + fmt(pos_r),
    ])
    res['paired'] = {k: (None if not v else {kk: vv for kk, vv in v.items() if kk != 'names'}) for k, v in
                     (('main', main_r), ('sensitivity', sens_r), ('placebo', place_r), ('positive', pos_r))}
    if main_r:
        top = sorted(main_r['names'], key=lambda t: abs(t[2] - t[1]), reverse=True)[:8]
        emit('중앙값 변화가 큰 클래스 8개(초, 전→후): ' + ', '.join(f'{n.split(".")[-1]} {b:.1f}→{a:.1f}' for n, b, a in top))
        emit()
    # 느린 모드
    emit('### 느린 모드 비율(전 기간 최소 ≤ 5초이고 전 기간 실행 ≥ 5회인 클래스의 실행 중 ≥ 15초)')
    emit()
    cand = [n for n, d in byclass.items() if len(d.get('S0', []) + d.get('S1', [])) >= 5 and min(d.get('S0', []) + d.get('S1', [])) <= 5]
    vb = [x for n in cand for p in ('S0', 'S1') for x in byclass[n].get(p, [])]
    va = [x for n in cand for p in ('S2', 'S3') for x in byclass[n].get(p, [])]
    kb, ka = sum(x >= 15 for x in vb), sum(x >= 15 for x in va)
    table(['대상 클래스', '전 실행', '전 ≥15초', '후 실행', '후 ≥15초', 'Fisher p'], [[len(cand), len(vb), ci_text(kb, len(vb)), len(va), ci_text(ka, len(va)),
                                                                  f'{fisher(kb, len(vb) - kb, ka, len(va) - ka):.3g}' if vb and va else '']])
    res['slow_mode'] = {'classes': len(cand), 'before': [kb, len(vb)], 'after': [ka, len(va)]}

    # ---- E2/F2. 사후 보정: 같은 head 반복 실험과 PR 자신의 실행을 바로잡은 집합
    emit('## E2·F2. 사후 보정 비교(사전 등록하지 않음: 영 대조가 실패해 규칙을 고쳤다)')
    emit()
    emit('보정 규칙: PR 실행은 **병합된 PR의 최종 head SHA인 시도만** 쓰고(중간 head와 닫힌 실험 PR 제외), 변경을 담은 PR(#604, #609, #613) 자신의 최종 head 실행은 변경 뒤 기간으로 센다. 기간은 `cperiod`.')
    emit()
    fh = [a for a in att if a['event'] == 'pull_request' and a['final_head'] == '1' and a['kind'] != 'cancelled']
    emit('보정 집합의 PR 전체 검사 시도 수: ' + ', '.join(f'{p}={sum(a["cperiod"] == p and a["kind"] == "full" for a in fh)}' for p in ('S0', 'S1', 'S2', 'S3')))
    emit()
    app2 = [j for j in app if j['final_head'] == '1']
    rows = []
    for p in ('S0', 'S1', 'S2', 'S3'):
        s = [float(j['seconds']) for j in app2 if j['cperiod'] == p]
        rows.append([p] + dist(s)[:5])
    table(['기간(cperiod)', 'n(shard job)', '최소', 'Q1/중앙/Q3', 'p90', '최대'], rows)

    def compare2(label, pa, pb):
        sa = [j for j in app2 if j['cperiod'] in pa]
        sb = [j for j in app2 if j['cperiod'] in pb]
        va, vb = vals(sa), vals(sb)
        if len(va) < 2 or len(vb) < 2:
            return [label, len(vb), len(va), '', '', '', '']
        lo, hi = cluster_boot_diff(grp(sa), grp(sb))
        return [label, len(vb), len(va), f'{statistics.median(vb):.0f} → {statistics.median(va):.0f}',
                f'{hl_shift(va, vb):+.1f}', f'[{lo:+.0f}, {hi:+.0f}]', f'{mannwhitney(va, vb):.3g}']
    table(['비교(보정 집합, 양수 = 후가 느림)', 'n 전', 'n 후', '중앙값 전→후', 'HL 이동', '중앙값 차이 95% 구간(PR 군집)', 'MWU p'], [
        compare2('주: S2+S3 대 S0+S1', ('S2', 'S3'), ('S0', 'S1')),
        compare2('영 대조: S3 대 S2 (#613은 PR에 영향 없음)', ('S3',), ('S2',)),
        compare2('영 대조: S1 대 S0 (#604는 시간 영향 주장 없음)', ('S1',), ('S0',)),
    ])
    # 짝지은 클래스 비교(보정 집합)
    fmap = {a['key']: a for a in fh}
    by2 = defaultdict(lambda: defaultdict(list))
    attempt_vals = defaultdict(lambda: defaultdict(list))  # 시도 → 클래스 → 값(무작위 분할 위약 대조용)
    with gzip.open(data / 'classtime.csv.gz', 'rt', encoding='utf-8') as f:
        next(f)
        for line in f:
            rid, at, sh, name, s = line.rstrip('\n').split(',')
            a = fmap.get((rid, at))
            if a:
                by2[name][a['cperiod']].append(float(s))
                attempt_vals[(rid, at)][name].append(float(s))
    main2 = paired(('S0', 'S1'), ('S2', 'S3'), by2)
    sens2 = paired(('S0', 'S1'), ('S2', 'S3'), by2, skip=touched)
    pos2 = paired(('S0', 'S1'), ('S2', 'S3'), by2, mult=0.85)
    before_keys = [k for k in attempt_vals if fmap[k]['cperiod'] in ('S0', 'S1')]
    placebo_pct, placebo_hit = [], 0
    reps = 100
    for _ in range(reps):
        half = set(random.sample(before_keys, len(before_keys) // 2))
        src = defaultdict(lambda: defaultdict(list))
        for k in before_keys:
            tag = 'x' if k in half else 'y'
            for name, vs in attempt_vals[k].items():
                src[name][tag].extend(vs)
        r = paired(('x',), ('y',), src, reps=300)
        if r:
            placebo_pct.append((math.exp(r['median_lr']) - 1) * 100)
            placebo_hit += (r['ci'][0] > 0 or r['ci'][1] < 0)
    table(['비교(보정 집합)', '클래스 수', '클래스 중앙값 비율 변화(중앙값)', '95% 구간(클래스 부트스트랩)', 'Wilcoxon p', '같은 클래스 집합의 중앙값 합 전→후(초)'], [
        ['주(S0+S1 대 S2+S3)'] + fmt(main2),
        ['민감도(수정한 클래스 제외)'] + fmt(sens2),
        ['양성 대조(후 × 0.85 합성)'] + fmt(pos2),
    ])
    emit(f'위약 대조(전 기간 시도를 무작위로 반으로 나눠 같은 비교, {reps}회): 클래스 중앙값 변화의 중앙값 {statistics.median(placebo_pct):+.2f}%, 범위 [{min(placebo_pct):+.1f}%, {max(placebo_pct):+.1f}%], 95% 구간이 0을 제외한 횟수 {placebo_hit}/{len(placebo_pct)}(거짓 양성률 기대값 약 5%).')
    emit()
    res['paired_corrected'] = {k: (None if not v else {kk: vv for kk, vv in v.items() if kk != 'names'}) for k, v in
                               (('main', main2), ('sensitivity', sens2), ('positive', pos2))}
    res['placebo_random'] = {'reps': len(placebo_pct), 'median_pct': statistics.median(placebo_pct), 'ci_excludes_zero': placebo_hit}
    if main2:
        top = sorted(main2['names'], key=lambda t: abs(t[2] - t[1]), reverse=True)[:8]
        emit('중앙값 변화가 큰 클래스 8개(초, 전→후): ' + ', '.join(f'{n.split(".")[-1]} {b:.1f}→{a:.1f}' for n, b, a in top))
        emit()
    # 느린 모드와 클래스 평균(탐색: 사후 추가)
    cand2 = [n for n, d in by2.items() if len(d.get('S0', []) + d.get('S1', [])) >= 5 and min(d.get('S0', []) + d.get('S1', [])) <= 5]
    vb2 = [x for n in cand2 for p in ('S0', 'S1') for x in by2[n].get(p, [])]
    va2 = [x for n in cand2 for p in ('S2', 'S3') for x in by2[n].get(p, [])]
    kb2, ka2 = sum(x >= 15 for x in vb2), sum(x >= 15 for x in va2)
    emit('느린 모드 비율(보정 집합, 사전 등록 정의 그대로):')
    emit()
    table(['대상 클래스', '전 실행', '전 ≥15초', '후 실행', '후 ≥15초', 'Fisher p'], [[len(cand2), len(vb2), ci_text(kb2, len(vb2)), len(va2), ci_text(ka2, len(va2)),
                                                                  f'{fisher(kb2, len(vb2) - kb2, ka2, len(va2) - ka2):.3g}' if vb2 and va2 else '']])
    res['slow_mode_corrected'] = {'classes': len(cand2), 'before': [kb2, len(vb2)], 'after': [ka2, len(va2)]}
    shared = [n for n, d in by2.items() if len(d.get('S0', []) + d.get('S1', [])) >= 3 and len(d.get('S2', []) + d.get('S3', [])) >= 3]
    mean_b = sum(statistics.mean(by2[n].get('S0', []) + by2[n].get('S1', [])) for n in shared)
    mean_a = sum(statistics.mean(by2[n].get('S2', []) + by2[n].get('S3', [])) for n in shared)
    sums = defaultdict(list)
    for j in app2:
        if j['classtime_sum'] != '':
            sums[j['cperiod']].append(float(j['classtime_sum']))
    emit(f'탐색(사후 추가): 두 기간에 모두 나온 클래스 {len(shared)}개의 클래스 평균 시간 합 {mean_b:.0f}초 → {mean_a:.0f}초({100 * (mean_a / mean_b - 1):+.1f}%). shard job의 클래스 시간 합 중앙값(초): ' +
         ', '.join(f'{p}={statistics.median(sums[p]):.0f}(n={len(sums[p])})' for p in ('S0', 'S1', 'S2', 'S3') if sums[p]))
    emit()

    over = defaultdict(list)
    for j in app2:
        if j['classtime_sum'] != '':
            over[j['cperiod']].append(float(j['seconds']) - 0.503 * float(j['classtime_sum']))
    emit('탐색(사후 추가): shard job 시간에서 EXP-076의 기울기 0.503 × 클래스 시간 합을 뺀 값(클래스 밖 고정 비용) 중앙값(초): ' +
         ', '.join(f'{p}={statistics.median(over[p]):.0f}(n={len(over[p])})' for p in ('S0', 'S1', 'S2', 'S3') if over[p]))
    emit()
    # 교정용 위약 분포: 느린 모드 비율과 클래스 평균 합도 같은 방식으로 무작위·시간순 분할의 분포를 본다
    def split_stats(src_attempts_x, src_attempts_y):
        def collect(keys):
            d = defaultdict(list)
            for k in keys:
                for name, vs in attempt_vals[k].items():
                    d[name].extend(vs)
            return d
        dx, dy = collect(src_attempts_x), collect(src_attempts_y)
        sh = [n for n in set(dx) & set(dy) if len(dx[n]) >= 3 and len(dy[n]) >= 3]
        sum_pct = 100 * (sum(statistics.mean(dy[n]) for n in sh) / sum(statistics.mean(dx[n]) for n in sh) - 1) if sh else float('nan')
        vx = [v for n in cand2 for v in dx.get(n, [])]
        vy = [v for n in cand2 for v in dy.get(n, [])]
        sx = sum(v >= 15 for v in vx) / len(vx) if vx else float('nan')
        sy = sum(v >= 15 for v in vy) / len(vy) if vy else float('nan')
        return sum_pct, 100 * sx, 100 * sy

    obs_keys_b = before_keys
    obs_keys_a = [k for k in attempt_vals if fmap[k]['cperiod'] in ('S2', 'S3')]
    obs = split_stats(obs_keys_b, obs_keys_a)
    pl_sum, pl_diff = [], []
    for _ in range(reps):
        half = set(random.sample(before_keys, len(before_keys) // 2))
        s_pct, s_x, s_y = split_stats([k for k in before_keys if k in half], [k for k in before_keys if k not in half])
        pl_sum.append(s_pct)
        pl_diff.append(s_y - s_x)
    ordered = sorted(before_keys, key=lambda k: fmap[k]['started'])
    t_sum, t_x, t_y = split_stats(ordered[:len(ordered) // 2], ordered[len(ordered) // 2:])
    emit('### 교정용 위약 분포(전 기간 시도만 사용)')
    emit()
    table(['지표', '관측(후 대 전)', '무작위 분할 100회 범위', '시간순 분할(앞 절반 대 뒤 절반)'], [
        ['클래스 평균 합 변화(%)', f'{obs[0]:+.1f}', f'[{min(pl_sum):+.1f}, {max(pl_sum):+.1f}]', f'{t_sum:+.1f}'],
        ['느린 모드 비율 차이(%p)', f'{obs[2] - obs[1]:+.2f}', f'[{min(pl_diff):+.2f}, {max(pl_diff):+.2f}]', f'{t_y - t_x:+.2f}'],
    ])
    res['calibration'] = {'observed': obs, 'placebo_sum_range': [min(pl_sum), max(pl_sum)], 'placebo_slow_diff_range': [min(pl_diff), max(pl_diff)],
                          'time_split': [t_sum, t_y - t_x]}
    # 느린 모드 비율 차이의 PR 군집 부트스트랩(같은 PR의 실행은 독립이 아니다)
    def clus_slow(keys):
        g = defaultdict(lambda: [0, 0])
        for k in keys:
            c = fmap[k]['pr'] or k[0]
            for name in cand2:
                for v in attempt_vals[k].get(name, []):
                    g[c][1] += 1
                    g[c][0] += v >= 15
        return g
    gb, ga = clus_slow(obs_keys_b), clus_slow(obs_keys_a)
    diffs = []
    kb_, ka_ = list(gb), list(ga)
    for _ in range(2000):
        sb_ = [gb[c] for c in random.choices(kb_, k=len(kb_))]
        sa_ = [ga[c] for c in random.choices(ka_, k=len(ka_))]
        tb, nb = sum(x[0] for x in sb_), sum(x[1] for x in sb_)
        ta, na = sum(x[0] for x in sa_), sum(x[1] for x in sa_)
        diffs.append(100 * (ta / na - tb / nb))
    emit(f'느린 모드 비율 후-전 차이의 PR 군집 부트스트랩 95% 구간: [{q(diffs, .025):+.2f}, {q(diffs, .975):+.2f}] %p (군집 전 {len(gb)}, 후 {len(ga)}).')
    emit()
    # ---- G. Q3
    emit('## G. Q3: DOWNLOADED_JARS와 Maven 429')
    emit()
    pushes_ok = [a for a in att if a['event'] == 'push' and a['verify'] == 'success' and ts(a['started']) >= ts(B['B604'])]
    w_rows = [j for j in jobs if j['kind'] == 'verify' and j['event'] == 'push' and j['conclusion'] == 'success'
              and ts(j['job_completed']) >= ts(B['B604']) and j['period'] != 'S0']
    W = min(ts(j['job_completed']) for j in w_rows) if w_rows else None
    emit(f'따뜻한 기준 시각 W(B604 뒤 첫 push `verify` 성공 종료) = {W.strftime("%Y-%m-%dT%H:%M:%SZ") if W else "없음"}')
    emit()
    jr = [j for j in jobs if j['kind'] == 'app' and j['downloaded_jars'] != '' and amap[j['key']]['kind'] != 'cancelled']
    rows = []
    for p in ('S0', 'S1', 'S2', 'S3'):
        for tag, cond in (('차가움(W 이전 시작)', lambda j: W is None or ts(j['started_attempt']) < W),
                          ('따뜻함(W 이후 시작)', lambda j: W is not None and ts(j['started_attempt']) >= W)):
            s = [j for j in jr if j['period'] == p and cond(j)]
            h = defaultdict(int)
            for j in s:
                h[int(j['downloaded_jars'])] += 1
            rows.append([p, tag, len(s), ', '.join(f'{k}개:{v}' for k, v in sorted(h.items())) or '-'])
    table(['기간', '캐시 상태', 'shard job(값 있음)', '히스토그램(값:job 수)'], rows)
    warm = [j for j in jr if W is not None and ts(j['started_attempt']) >= W]
    nz = [j for j in warm if int(j['downloaded_jars']) > 0]
    res['jars'] = {'warm_jobs': len(warm), 'warm_nonzero': len(nz)}
    emit(f'따뜻한 shard job {ci_text(len(nz), len(warm)).replace("%", "% 0아님")}')
    emit()
    nzpr = {}
    p_path = data / 'nonzero_jars_prs.csv'
    if p_path.exists():
        nzpr = {r['pr']: r for r in read_csv(p_path)}
    emit('따뜻한 시도에서 0이 아닌 job: ' + (', '.join(f'run {j["run_id"]}/{j["attempt"]} shard{j["shard"]}={j["downloaded_jars"]} PR#{j["pr"]} 빌드 파일 변경={"예(" + nzpr.get(j["pr"], {}).get("build_files", "?") + ")" if nzpr.get(j["pr"], {}).get("build_files") else "아니오"}' for j in nz) or '없음'))
    emit()
    push_jars = [j for j in jr if j['event'] == 'push' and j['period'] == 'S3']
    push_s3 = [a for a in att if a['event'] == 'push' and a['period'] == 'S3' and a['kind'] != 'cancelled']
    emit(f'H9: S3 push 시도 {len(push_s3)}건 중 app 단계가 돈 시도 {sum(int(a["app_jobs_ran"]) > 0 for a in push_s3)}건, DOWNLOADED_JARS 줄이 있는 job {len(push_jars)}개.')
    emit()
    emit('### Maven 429(실패한 job 로그, 취소 제외)')
    emit()
    full = [a for a in att if a['kind'] == 'full']
    rows = []
    for label, ps in (('S0 (#604 전)', ('S0',)), ('S1+S2+S3 (#604 뒤)', ('S1', 'S2', 'S3'))):
        fa = [a for a in full if a['period'] in ps]
        fj = [j for j in jobs if j['period'] in ps and j['kind'] in ('app', 'verify') and amap[j['key']]['kind'] == 'full']
        k_att = len({j['key'] for j in fj if j['conclusion'] == 'failure' and (num(j['n429']) or 0) > 0})
        k_job = sum(j['conclusion'] == 'failure' and (num(j['n429']) or 0) > 0 for j in fj)
        any429 = sum((num(j['n429']) or 0) > 0 for j in fj)
        http = sum((num(j['nhttp']) or 0) > 0 for j in fj)
        rows.append([label, ci_text(k_att, len(fa)), ci_text(k_job, len(fj)), any429, http])
    table(['기간', '429로 실패한 전체 검사 시도', '429로 실패한 job', '429 줄이 있는 job(성공 포함)', 'HTTP 오류 줄이 있는 job'], rows)
    emit('S0에서 429로 실패한 job: ' + ', '.join(
        f'run {j["run_id"]}/{j["attempt"]} {j["kind"]}{j["shard"]} {j["event"]} {amap[j["key"]]["branch"]} {j["started_attempt"][5:16]}'
        for j in jobs if j['period'] == 'S0' and j['conclusion'] == 'failure' and (num(j['n429']) or 0) > 0))
    emit()
    latest = {}
    for a in att:
        if int(a['attempt']) >= int(latest.get(a['run_id'], {'attempt': 0})['attempt']):
            latest[a['run_id']] = a
    hid = [j for j in jobs if j['conclusion'] == 'failure' and (num(j['n429']) or 0) > 0 and latest[j['run_id']]['conclusion'] != 'failure']
    emit(f'429로 실패했지만 같은 실행의 마지막 시도 결론이 failure가 아니라 `gh run list`에서는 보이지 않는 job: {len(hid)}개({", ".join(sorted({j["run_id"] for j in hid}))}).')
    emit()
    s0 = [a for a in full if a['period'] == 'S0']
    s0_before = [a for a in s0 if ts(a['started']) < ts('2026-11-24T11:37:00Z')]
    k0 = len({j['key'] for j in jobs if j['key'] in {a['key'] for a in s0_before} and j['conclusion'] == 'failure' and (num(j['n429']) or 0) > 0})
    emit(f'하네스 재현: EXP-075 창(2026-11-24T11:37Z까지)에서 이 규칙으로 센 샤딩 전체 검사 시도 {len(s0_before)}건, 429 실패 {k0}건(EXP-075는 같은 창에서 118건 중 3건, 창 시작 11-23 12:11Z 이전 실행 일부는 이 창에 없음).')
    emit()

    # ---- H. Q4
    emit('## H. Q4: push 생략의 전제와 job 분')
    emit()
    emit('저장소 설정(수집 시각의 값): `' + json.dumps(meta['repo_settings'], ensure_ascii=False) + '`')
    emit()
    emit('브랜치 보호: `' + json.dumps(meta['branch_protection'], ensure_ascii=False) + '`, 규칙 집합: 개수 ' + str(meta['branch_rules_count']) + ', 유형 ' + str(meta['branch_rules_types']) + ', strict 값 ' + str(meta['branch_rules_strict']))
    emit()
    t613 = ts(B['B613'])
    rows = []
    for label, cs in (('전체(창 안 main 커밋)', commits), ('B613 이후', [c for c in commits if ts(c['date']) >= t613])):
        pr_c = [c for c in cs if c['pr']]
        bb = [c for c in pr_c if c['behind_by'] != '']
        rows.append([label, len(cs), sum(c['parents'] == '1' for c in cs), len(pr_c), len(bb), sum(c['behind_by'] == '0' for c in bb),
                     ','.join(c['pr'] for c in bb if c['behind_by'] != '0') or '-'])
    table(['커밋 집합', '커밋', '부모 1개(squash/rebase 형태)', 'PR 번호가 있는 커밋', 'behind_by 확인', 'behind_by = 0', 'behind_by > 0인 PR'], rows)
    res['q4_commits'] = rows

    def minutes(rows_):
        return [int(a['job_seconds']) / 60 for a in rows_]
    emit('### 시도별 job 분(합계, 분)')
    emit()
    rows = []
    sets = {}
    for ev, label, cond in (
        ('push', 'push 전체 검사(S0~S2, 코드 변경)', lambda a: a['event'] == 'push' and a['kind'] == 'full' and a['period'] in ('S0', 'S1', 'S2')),
        ('push', 'push verify 전용(S3, 코드 변경)', lambda a: a['event'] == 'push' and a['kind'] == 'verify_only' and a['period'] == 'S3'),
        ('push', 'push 전체 검사(S3, 있다면)', lambda a: a['event'] == 'push' and a['kind'] == 'full' and a['period'] == 'S3'),
        ('pr', 'PR 전체 검사 S0', lambda a: a['event'] == 'pull_request' and a['kind'] == 'full' and a['period'] == 'S0'),
        ('pr', 'PR 전체 검사 S1', lambda a: a['event'] == 'pull_request' and a['kind'] == 'full' and a['period'] == 'S1'),
        ('pr', 'PR 전체 검사 S2', lambda a: a['event'] == 'pull_request' and a['kind'] == 'full' and a['period'] == 'S2'),
        ('pr', 'PR 전체 검사 S3', lambda a: a['event'] == 'pull_request' and a['kind'] == 'full' and a['period'] == 'S3'),
    ):
        s = [a for a in att if cond(a) and a['conclusion'] == 'success']
        sets[label] = s
        v = minutes(s)
        ceil_ = [int(a['job_minutes_ceil']) for a in s]
        rows.append([label, len(s)] + ([f'{min(v):.1f}', f'{q(v, .25):.1f}/{q(v, .5):.1f}/{q(v, .75):.1f}', f'{q(v, .9):.1f}', f'{max(v):.1f}', f'{statistics.median(ceil_):.0f}'] if v else ['', '', '', '', '']))
    table(['집합(결론 success만)', 'n', '최소', 'Q1/중앙/Q3', 'p90', '최대', '올림 분 중앙값'], rows)
    pb, pa_ = minutes(sets['push 전체 검사(S0~S2, 코드 변경)']), minutes(sets['push verify 전용(S3, 코드 변경)'])
    if pb and pa_:
        red = 1 - statistics.median(pa_) / statistics.median(pb)
        emit(f'**음성 대조 (d)**: push 중앙값 {statistics.median(pb):.1f}분 → {statistics.median(pa_):.1f}분(감소 {100 * red:.1f}%), 분포가 겹치는가: 후 최대 {max(pa_):.1f} < 전 최소 {min(pb):.1f} = {max(pa_) < min(pb)}, MWU p = {mannwhitney(pa_, pb):.3g}.')
        res['push_minutes'] = {'before_median': statistics.median(pb), 'after_median': statistics.median(pa_), 'reduction': red, 'n': [len(pb), len(pa_)]}
    emit()
    pr2, pr3 = minutes(sets['PR 전체 검사 S2']), minutes(sets['PR 전체 검사 S3'])
    if pr2 and pr3:
        emit(f'영 대조: PR 시도 job 분 S2 {statistics.median(pr2):.2f} → S3 {statistics.median(pr3):.2f}분, HL 이동 {hl_shift(pr3, pr2):+.2f}분, MWU p = {mannwhitney(pr3, pr2):.3g}.')
        emit()
    # 시간당 합계
    emit('### 이벤트별 job 분 총합과 시간당 값(성공·실패 모두, 취소 제외, 문서 전용 포함)')
    emit()
    rows = []
    for i, p in enumerate(('S0', 'S1', 'S2', 'S3')):
        hours = (bounds[i + 1] - bounds[i]).total_seconds() / 3600
        row = [p, f'{hours:.1f}']
        for ev in ('pull_request', 'push'):
            tot = sum(int(a['job_seconds']) for a in att if a['period'] == p and a['event'] == ev and a['kind'] != 'cancelled') / 60
            row += [f'{tot:.0f}', f'{tot / hours:.1f}']
        rows.append(row)
    table(['기간', '시간(h)', 'PR 총 분', 'PR 분/h', 'push 총 분', 'push 분/h'], rows)
    pf = [a for a in att if a['event'] == 'push' and a['kind'] in ('full', 'verify_only') and a['conclusion'] == 'failure']
    why = defaultdict(int)
    for a in pf:
        js = [j for j in jobs if j['key'] == a['key'] and j['conclusion'] == 'failure']
        why['429' if any((num(j['n429']) or 0) > 0 for j in js) else ('LocationFault' if any(j['loc_failed'] == '1' for j in js) else '그 밖')] += 1
    n_pre = sum(1 for a in att if a['event'] == 'push' and a['kind'] == 'full' and a['period'] in ('S0', 'S1', 'S2'))
    n_post = sum(1 for a in att if a['event'] == 'push' and a['kind'] == 'verify_only')
    emit(f'main push 실패(취소 제외): #613 전 전체 검사 {ci_text(sum(1 for a in pf if a["kind"] == "full"), n_pre)}, #613 뒤 verify 전용 {ci_text(sum(1 for a in pf if a["kind"] == "verify_only"), n_post)}. 실패 원인: ' + ', '.join(f'{k} {v}건' for k, v in why.items()))
    emit()
    emit('main push 실패(S2·S3, 취소 제외): ' + (', '.join(f'run {a["run_id"]}/{a["attempt"]} {a["started"]} {a["period"]} verify={a["verify"]} app_ran={a["app_jobs_ran"]}' for a in att if a['event'] == 'push' and a['period'] in ('S2', 'S3') and a['conclusion'] == 'failure') or '없음'))
    emit()
    (data.parent / 'results.md').write_text('\n'.join(OUT), encoding='utf-8')
    (data.parent / 'results.json').write_text(json.dumps(res, indent=2, ensure_ascii=False, default=str), encoding='utf-8')
    print('\n'.join(OUT))


def touched_classes(repo):
    """창 안에서 테스트 파일이 수정된 클래스의 단순 이름 집합(짝지은 비교의 민감도에서 뺀다)."""
    out = subprocess.run(['git', '-C', repo, 'log', '--since=2026-11-23T12:11:38Z', '--name-only', '--format=', 'origin/main'],
                         capture_output=True, text=True).stdout
    return {line.rsplit('/', 1)[-1][:-5] for line in out.split('\n') if line.endswith('.java') and '/src/test/' in line}


if __name__ == '__main__':
    main()
