#!/usr/bin/env bash
# 한 번 실행으로 끝까지 간다: DB 기동 → 복제 대기 → 앱 기동 → 부하 · CPU 수집 → 보고서 저장 → 앱 종료 · 컨테이너 삭제
# 사용: scripts/run-lab.sh [--concurrency 32] [--duration 30] [--read-ratio 0.8] [--scan-rows 20000] [--no-open]
set -euo pipefail
cd "$(dirname "$0")/.."

CONCURRENCY=32 DURATION=30 READ_RATIO=0.8 SCAN_ROWS=20000 OPEN=1 PORT=${PORT:-8080}
while [ $# -gt 0 ]; do
  case "$1" in
    --concurrency) CONCURRENCY=$2; shift 2 ;;
    --duration) DURATION=$2; shift 2 ;;
    --read-ratio) READ_RATIO=$2; shift 2 ;;
    --scan-rows) SCAN_ROWS=$2; shift 2 ;;
    --no-open) OPEN=0; shift ;;
    *) echo "알 수 없는 옵션: $1" >&2; exit 2 ;;
  esac
done

# Rancher Desktop 은 소켓 경로가 기본값과 다르다
if [ -z "${DOCKER_HOST:-}" ] && [ -S "$HOME/.rd/docker.sock" ]; then export DOCKER_HOST="unix://$HOME/.rd/docker.sock"; fi
docker info >/dev/null 2>&1 || { echo "Docker 에 연결하지 못했다. Rancher Desktop 이나 Docker Desktop 을 켠다" >&2; exit 1; }

API="http://127.0.0.1:$PORT"
WORK=$(mktemp -d)
APP_PID=""
mkdir -p reports
OUT="reports/report-$(date +%Y%m%d-%H%M%S).html"

cleanup() {
  local code=$?
  echo "[정리] 앱 종료 · 컨테이너 삭제"
  [ -n "$APP_PID" ] && kill "$APP_PID" 2>/dev/null && wait "$APP_PID" 2>/dev/null || true
  docker compose down -v >/dev/null 2>&1 || true
  if [ $code -ne 0 ] && [ -s "$WORK/app.log" ]; then echo "[실패] 종료 코드 $code. 앱 로그 끝부분:" >&2; tail -20 "$WORK/app.log" >&2; fi
  rm -rf "$WORK"
  exit $code
}
trap cleanup EXIT INT TERM

wait_until() {  # 설명 · 제한 초 · 조건 명령
  local what=$1 limit=$2; shift 2
  for _ in $(seq 1 "$limit"); do "$@" >/dev/null 2>&1 && return 0; sleep 1; done
  echo "[실패] $what: ${limit}초 안에 준비되지 않았다" >&2; return 1
}
replica_seeded() { [ "$(docker exec lab-mysql-replica mysql -uroot -proot -N -e 'SELECT COUNT(*) FROM sample_split.usage_sample' 2>/dev/null)" = "200000" ]; }

echo "[1/5] DB 기동 (소스 · 레플리카, 깨끗한 상태에서)"
docker compose down -v >/dev/null 2>&1 || true
docker compose up -d >/dev/null 2>&1
wait_until "레플리카 초기 데이터 복제" 240 replica_seeded

echo "[2/5] 앱 기동"
./gradlew -q bootJar
JAR=$(ls -t build/libs/*.jar | grep -v plain | head -1)
java -jar "$JAR" --server.port="$PORT" >"$WORK/app.log" 2>&1 &
APP_PID=$!
wait_until "앱 기동" 120 curl -sf "$API/api/check" || { tail -30 "$WORK/app.log" >&2; exit 1; }

echo "[3/5] 부하 실행: 작업자 $CONCURRENCY · 구성당 ${DURATION}초 · 읽기 비율 $READ_RATIO · 집계 ${SCAN_ROWS}건"
BODY=$(printf '{"mode":"BOTH","concurrency":%s,"durationSec":%s,"readRatio":%s,"scanRows":%s}' "$CONCURRENCY" "$DURATION" "$READ_RATIO" "$SCAN_ROWS")
curl -sf -X POST "$API/api/runs" -H 'Content-Type: application/json' -d "$BODY" >/dev/null
: >"$WORK/cpu.tsv"
while :; do
  PROGRESS=$(curl -sf "$API/api/progress")
  echo "$PROGRESS" | grep -q '"running":true' || break
  STAGE=$(echo "$PROGRESS" | sed -n 's/.*"stage":"\([^"]*\)".*/\1/p')
  if [ "$STAGE" = "single" ] || [ "$STAGE" = "split" ]; then
    STATS=$(docker stats --no-stream --format '{{.Name}} {{.CPUPerc}}' lab-mysql-source lab-mysql-replica | tr -d '%')
    SRC=$(echo "$STATS" | awk '$1=="lab-mysql-source"{print $2}')
    REP=$(echo "$STATS" | awk '$1=="lab-mysql-replica"{print $2}')
    printf '%s\t%s\t%s\n' "$STAGE" "$SRC" "$REP" >>"$WORK/cpu.tsv"
    printf '\r      %s 실행 중 · 소스 CPU %s%% · 레플리카 CPU %s%%   ' "$STAGE" "$SRC" "$REP"
  fi
done
echo
echo "$PROGRESS" | grep -q '"error":null' || { echo "[실패] 부하 실행: $PROGRESS" >&2; exit 1; }

echo "[4/5] 보고서 작성"
RUN_ID=$(curl -sf "$API/api/runs" | python3 -c 'import json,sys; print(json.load(sys.stdin)[0]["id"])')
CPU_JSON=$(python3 - "$WORK/cpu.tsv" <<'PY'
import json, sys
acc = {}
for line in open(sys.argv[1]):
    stage, src, rep = line.split()
    a = acc.setdefault(stage, [0.0, 0.0, 0])
    a[0] += float(src); a[1] += float(rep); a[2] += 1
print(json.dumps({k: {"source": round(v[0] / v[2], 1), "replica": round(v[1] / v[2], 1), "samples": v[2]} for k, v in acc.items()}))
PY
)
curl -sf -X POST "$API/api/runs/$RUN_ID/cpu" -H 'Content-Type: application/json' -d "$CPU_JSON" >/dev/null
curl -sf "$API/report.html?id=$RUN_ID" -o "$OUT"
python3 - "$OUT" <<'PY'
import re, sys
html = open(sys.argv[1]).read()
part = html[html.index("5. 결론"):]
print("\n".join("      " + t.strip() for t in re.sub(r"<[^>]+>", "\n", part).splitlines() if t.strip()))
PY

echo "[5/5] 보고서: $OUT"
[ "$OPEN" = "1" ] && command -v open >/dev/null && open "$OUT" || true
