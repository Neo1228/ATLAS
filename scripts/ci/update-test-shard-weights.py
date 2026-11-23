#!/usr/bin/env python3
"""테스트 shard 가중치 파일을 최근 실행 결과로 갱신한다.

사용법: 전체 테스트를 한 번 실행한 뒤(./gradlew :application-module:test) 저장소 루트에서 실행한다.
    python3 scripts/ci/update-test-shard-weights.py

ATLAS-application/build/test-results/test/TEST-*.xml의 클래스별 실행 시간(초)을 합산해
ATLAS-application/src/test/resources/ci/test-shard-weights.properties에 쓴다.
가중치는 shard 균형에만 쓰이며 틀려도 테스트가 빠지지 않는다. 클래스가 추가·삭제되면 갱신을 권장한다.
"""
import glob
import re
import sys
from collections import defaultdict

RESULTS = "ATLAS-application/build/test-results/test/TEST-*.xml"
TARGET = "ATLAS-application/src/test/resources/ci/test-shard-weights.properties"

weights = defaultdict(float)
for path in glob.glob(RESULTS):
    head = open(path, encoding="utf-8").read(1200)
    match = re.search(r'name="([^"]+)" tests="\d+".*?time="([\d.]+)"', head)
    if match:
        weights[match.group(1).split("$")[0]] += float(match.group(2))

if not weights:
    sys.exit("테스트 결과 XML이 없습니다. 먼저 ./gradlew :application-module:test 를 실행하세요.")

lines = [
    "# 테스트 shard 배정용 가중치(초). scripts/ci/update-test-shard-weights.py로 생성한다.",
    "# 배정에만 쓰이며 값이 틀려도 테스트가 빠지지 않는다.",
]
lines += ["%s=%.1f" % (name, max(seconds, 0.1)) for name, seconds in sorted(weights.items())]
with open(TARGET, "w", encoding="utf-8") as out:
    out.write("\n".join(lines) + "\n")
print("%d개 클래스, 합계 %.0f초 -> %s" % (len(weights), sum(weights.values()), TARGET))
