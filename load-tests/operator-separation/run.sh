#!/usr/bin/env bash
# EXP-096 E1 한 줄 실행: load-tests/operator-separation/run.sh
# 환경 변수: N(시나리오·변형당 반복, 기본 200), TAG(컨테이너 label atlasexp=<TAG>, 기본 h7), OUT(원본 결과 출력 폴더, 기본 /tmp/exp096-out. 저장소에 넣지 않는다)
# 컨테이너 1개(postgres:18.6)를 만들고 끝나면 지운다. 이미지 기본 설정이며 값은 맥락이다.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
N="${N:-200}"
TAG="${TAG:-h7}"
OUT="${OUT:-/tmp/exp096-out}"
MIGRATIONS="$ROOT/atlas-application/src/main/resources/db/migration"
NAME="${TAG}-pg-opsep"
mkdir -p "$OUT"
cleanup() { docker rm -f "$NAME" >/dev/null 2>&1 || true; }
trap cleanup EXIT
psql_db() { local db="$1"; shift; docker exec -i "$NAME" psql -U postgres -d "$db" -v ON_ERROR_STOP=1 -X -q "$@"; }
apply() { local db="$1"; shift; for f in "$@"; do psql_db "$db" -o /dev/null < "$f"; done; }

echo "uptime(before): $(uptime)" | tee "$OUT/environment.txt"
docker run -d --label "atlasexp=$TAG" --name "$NAME" -e POSTGRES_PASSWORD=x postgres:18.6 >/dev/null
for _ in $(seq 1 60); do
  if docker exec "$NAME" psql -U postgres -c 'select 1' >/dev/null 2>&1; then sleep 2; \
     docker exec "$NAME" psql -U postgres -c 'select 1' >/dev/null 2>&1 && break; fi
  sleep 1
done
docker exec "$NAME" psql -U postgres -tAc 'select version()' | tee -a "$OUT/environment.txt"
docker stats --no-stream --format 'docker stats {{.Name}} cpu={{.CPUPerc}} mem={{.MemUsage}}' "$NAME" | tee -a "$OUT/environment.txt"

PRE=()
while IFS= read -r f; do PRE+=("$f"); done < <(ls "$MIGRATIONS"/V*.sql | grep -v 'V5__payout_operator_identity' | sort -V)
V5="$(ls "$MIGRATIONS"/V5__payout_operator_identity*.sql)"

echo "== A. migration additive drill (V5 전후 기존 행 해시, 기존 흐름, 되돌림, 재적용)"
psql_db postgres -c 'create database pre' >/dev/null
apply pre "${PRE[@]}" "$HERE/fixtures.sql"
SNAPSHOT="select md5(coalesce(string_agg(t.x, '|' order by t.x), '')) from (
  select p::text x from settlement_payout p union all select o::text from settlement_payout_dispatch_outbox o
  union all select a::text from seller_payout_profile_action a union all select s::text from seller_payout_profile s
  union all select u::text from user_account u union all select oa::text from settlement_payout_operator_action oa) t"
psql_db pre -c "do \$\$ declare i int; s record; begin for i in 1..50 loop select * into s from exp_seed('legacy-operator','legacy-operator');
  insert into settlement_payout_operator_action(payout_id, idempotency_key, action, actor, reason, created_at)
  values (s.payout, 'legacy-'||i, 'PAYOUT_APPROVED', 'legacy-operator', 'legacy', now()); end loop; end \$\$"
BEFORE="$(psql_db pre -tA -c "$SNAPSHOT")"
ROWS_BEFORE="$(psql_db pre -tA -c "select (select count(*) from settlement_payout)||','||(select count(*) from settlement_payout_operator_action)")"
apply pre "$V5"
AFTER="$(psql_db pre -tA -c "$SNAPSHOT")"
apply pre "$HERE/undo.sql"
GONE="$(psql_db pre -tA -c "select (select count(*) from pg_trigger where tgname='settlement_payout_operator_action_separation')||','||(select count(*) from pg_proc where proname='enforce_payout_operator_separation')||','||(select count(*) from pg_class where relname='payout_operator_account')||','||(select count(*) from pg_class where relname='uq_settlement_payout_operator_action_approved')")"
AFTER_UNDO="$(psql_db pre -tA -c "$SNAPSHOT")"
apply pre "$V5"
REAPPLIED="$(psql_db pre -tA -c "select count(*) from pg_trigger where tgname='settlement_payout_operator_action_separation'")"
LEGACY_FLOW="$(psql_db pre -tA -c "do \$\$ declare s record; begin select * into s from exp_seed('legacy-operator','legacy-operator');
  insert into settlement_payout_operator_action(payout_id, idempotency_key, action, actor, reason, created_at)
  values (s.payout, 'legacy-after-v5', 'PAYOUT_APPROVED', 'legacy-operator', 'legacy', now()); end \$\$; select 'ok'" | tail -1)"

{
  echo -e "check\tvalue"
  echo -e "rows_before(payout,action)\t$ROWS_BEFORE"
  echo -e "snapshot_equal_after_V5\t$([ "$BEFORE" = "$AFTER" ] && echo yes || echo NO)"
  echo -e "legacy_actor_flow_after_V5\t$LEGACY_FLOW"
  echo -e "objects_after_undo(trigger,function,table,index)\t$GONE"
  echo -e "snapshot_equal_after_undo\t$([ "$BEFORE" = "$AFTER_UNDO" ] && echo yes || echo NO)"
  echo -e "trigger_count_after_reapply\t$REAPPLIED"
} | tee "$OUT/migration-drill.tsv"

echo "== B. 강제 수단 비교 (N=$N)"
psql_db postgres -c 'create database exp' >/dev/null
apply exp "${PRE[@]}" "$V5"
{
  cat "$HERE/fixtures.sql" "$HERE/scenarios.sql"
  for s in x0 x1 x2 x3; do echo "CALL exp_run('$s', $N);"; done
} | psql_db exp -o /dev/null
psql_db exp -A -F $'\t' -c "select scenario, variant, n, accepted, rejected_by_rule, other_error from exp_result order by scenario, variant" \
  | tee "$OUT/enforcement-comparison.tsv"

echo "== C. trigger 비용과 실행 계획"
psql_db exp < "$HERE/timing.sql" | tee "$OUT/trigger-cost-and-plan.txt"
echo "uptime(after): $(uptime)" | tee -a "$OUT/environment.txt"
docker ps -a --filter "label=atlasexp=$TAG" --format '{{.Names}}' | sed 's/^/remaining container: /'
