#!/usr/bin/env python3
"""테스트 shard 가중치 파일을 최근 실행 결과로 갱신한다.

사용법(저장소 루트에서 실행):
    python3 scripts/ci/update-test-shard-weights.py            # 로컬 전체 실행 결과(XML) 사용
    python3 scripts/ci/update-test-shard-weights.py --run <id> # Backend CI 실행 로그의 CLASSTIME 줄 사용(권장)

로컬 값은 runner와 CPU가 달라 클래스별 상대 비용이 어긋날 수 있으므로 CI 실행 값을 권장한다.

atlas-application/build/test-results/test/TEST-*.xml의 클래스별 실행 시간(초)을 합산해
atlas-application/src/test/resources/ci/test-shard-weights.properties에 쓴다.
가중치는 shard 균형에만 쓰이며 틀려도 테스트가 빠지지 않는다. 클래스가 추가·삭제되면 갱신을 권장한다.
"""
import glob
import re
import sys
from collections import defaultdict

RESULTS = "atlas-application/build/test-results/test/TEST-*.xml"
TARGET = "atlas-application/src/test/resources/ci/test-shard-weights.properties"

weights = defaultdict(float)
if len(sys.argv) == 3 and sys.argv[1] == "--run":
    import subprocess
    log = subprocess.run(["gh", "run", "view", sys.argv[2], "--log"], capture_output=True, text=True).stdout
    for match in re.finditer(r"CLASSTIME (\S+) ([\d.]+)", log):
        weights[match.group(1).split("$")[0]] += float(match.group(2))
else:
    for path in glob.glob(RESULTS):
        head = open(path, encoding="utf-8").read(1200)
        match = re.search(r'name="([^"]+)" tests="\d+".*?time="([\d.]+)"', head)
        if match:
            weights[match.group(1).split("$")[0]] += float(match.group(2))

if not weights:
    sys.exit("가중치로 쓸 실행 결과가 없습니다. ./gradlew :atlas-application:test 를 먼저 실행하거나 --run <id>를 쓰세요.")

lines = [
    "# 테스트 shard 배정용 가중치(초). scripts/ci/update-test-shard-weights.py로 생성한다.",
    "# 배정에만 쓰이며 값이 틀려도 테스트가 빠지지 않는다.",
]
lines += ["%s=%.1f" % (name, max(seconds, 0.1)) for name, seconds in sorted(weights.items())]
with open(TARGET, "w", encoding="utf-8") as out:
    out.write("\n".join(lines) + "\n")
print("%d개 클래스, 합계 %.0f초 -> %s" % (len(weights), sum(weights.values()), TARGET))
