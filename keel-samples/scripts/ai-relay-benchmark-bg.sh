#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
STATE_DIR="$ROOT_DIR/keel-samples/build/reports/ai-relay-benchmark-state"
PID_FILE="$STATE_DIR/current.pid"
TOTAL_CASES="${AI_RELAY_BENCHMARK_TOTAL_CASES:-120}"

usage() {
  cat <<'EOF'
Usage:
  keel-samples/scripts/ai-relay-benchmark-bg.sh start [phase]
  keel-samples/scripts/ai-relay-benchmark-bg.sh status
  keel-samples/scripts/ai-relay-benchmark-bg.sh progress [output-dir]
  keel-samples/scripts/ai-relay-benchmark-bg.sh tail
  keel-samples/scripts/ai-relay-benchmark-bg.sh stop

Defaults:
  phase: full
  output: keel-samples/build/reports/ai-relay-benchmark-<phase>-<timestamp>

Environment:
  AI_RELAY_BENCHMARK_OUTPUT_DIR   Override output directory for start.
  AI_RELAY_BENCHMARK_TOTAL_CASES  Override total case count for progress.
EOF
}

is_running() {
  local pid="$1"
  [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null
}

read_state() {
  if [[ -f "$PID_FILE" ]]; then
    IFS='|' read -r STATE_PID STATE_PHASE STATE_OUTPUT STATE_STARTED < "$PID_FILE"
  else
    STATE_PID=""
    STATE_PHASE=""
    STATE_OUTPUT=""
    STATE_STARTED=""
  fi
}

start_benchmark() {
  local phase="${1:-full}"
  mkdir -p "$STATE_DIR"
  read_state
  if is_running "$STATE_PID"; then
    echo "Benchmark already running: pid=$STATE_PID phase=$STATE_PHASE output=$STATE_OUTPUT"
    exit 1
  fi

  local stamp output log started
  stamp="$(date +%Y%m%d-%H%M%S)"
  output="${AI_RELAY_BENCHMARK_OUTPUT_DIR:-$ROOT_DIR/keel-samples/build/reports/ai-relay-benchmark-$phase-$stamp}"
  output="$(mkdir -p "$output" && cd "$output" && pwd -P)"
  log="$output/benchmark.log"
  started="$(date +%s)"

  (
    cd "$ROOT_DIR"
    nohup ./gradlew :keel-samples:aiRelayBenchmark \
      -PbenchmarkPhase="$phase" \
      -PbenchmarkOutputDir="$output" \
      > "$log" 2>&1 &
    echo "$!|$phase|$output|$started" > "$PID_FILE"
  )

  read_state
  echo "Started benchmark: pid=$STATE_PID phase=$phase"
  echo "Output: $output"
  echo "Log: $log"
}

status_benchmark() {
  read_state
  if is_running "$STATE_PID"; then
    echo "RUNNING pid=$STATE_PID phase=$STATE_PHASE output=$STATE_OUTPUT"
  elif [[ -n "$STATE_PID" ]]; then
    echo "STOPPED pid=$STATE_PID phase=$STATE_PHASE output=$STATE_OUTPUT"
  else
    echo "No benchmark state found."
  fi
}

progress_benchmark() {
  local output="${1:-}"
  read_state
  if [[ -z "$output" ]]; then
    output="$STATE_OUTPUT"
  fi
  if [[ -z "$output" ]]; then
    echo "No output directory provided and no benchmark state found."
    exit 1
  fi

  local summary="$output/summary.csv"
  if [[ ! -f "$summary" ]]; then
    echo "No summary yet."
    echo "Output: $output"
    status_benchmark
    exit 0
  fi

  python3 - "$summary" "$TOTAL_CASES" "$STATE_STARTED" "$STATE_PID" <<'PY'
import csv
import datetime as dt
import os
import signal
import sys
import time

summary_path, total_cases_raw, started_raw, pid_raw = sys.argv[1:5]
total_cases = int(total_cases_raw)
rows = []
with open(summary_path, newline="") as f:
    rows = list(csv.DictReader(f))

case_order = []
seen = set()
for row in rows:
    case_id = row["caseId"]
    if case_id not in seen:
        seen.add(case_id)
        case_order.append(case_id)

running = False
if pid_raw:
    try:
        os.kill(int(pid_raw), 0)
        running = True
    except OSError:
        running = False

unique_seen = len(case_order)
completed = max(0, unique_seen - 1) if running else unique_seen
current = case_order[-1] if case_order else "<none>"
now = int(time.time())
started = int(started_raw) if started_raw.isdigit() else None
elapsed_hours = (now - started) / 3600 if started else None
rate = completed / elapsed_hours if elapsed_hours and completed else None
eta_hours = (total_cases - completed) / rate if rate else None

print(f"progress={completed}/{total_cases} ({completed / total_cases:.1%})")
print(f"current_case={current}")
print(f"summary_rows={len(rows)} unique_cases_seen={unique_seen} running={str(running).lower()}")
if elapsed_hours is not None:
    print(f"elapsed_hours={elapsed_hours:.2f}")
if rate is not None:
    print(f"avg_cases_per_hour={rate:.2f} rough_eta_hours={eta_hours:.1f}")

if rows:
    print("last_steps:")
    for row in rows[-5:]:
        print(
            f"- {row['caseId']} c={row['concurrency']} stable={row['stable']} "
            f"reason={row['instabilityReason'] or '-'} "
            f"requests={row['requests']} errRate={row['errorRate']} p99Ms={row['p99Ms']}"
        )
PY
}

tail_benchmark() {
  read_state
  if [[ -z "$STATE_OUTPUT" ]]; then
    echo "No benchmark state found."
    exit 1
  fi
  tail -n 80 -f "$STATE_OUTPUT/benchmark.log"
}

stop_benchmark() {
  read_state
  if ! is_running "$STATE_PID"; then
    echo "No running benchmark found."
    exit 0
  fi

  kill -TERM "$STATE_PID" 2>/dev/null || true
  sleep 3
  if is_running "$STATE_PID"; then
    echo "Benchmark still running after TERM; sending KILL to pid=$STATE_PID"
    kill -KILL "$STATE_PID" 2>/dev/null || true
  fi
  echo "Stopped benchmark: pid=$STATE_PID output=$STATE_OUTPUT"
}

cmd="${1:-}"
case "$cmd" in
  start)
    start_benchmark "${2:-full}"
    ;;
  status)
    status_benchmark
    ;;
  progress)
    progress_benchmark "${2:-}"
    ;;
  tail)
    tail_benchmark
    ;;
  stop)
    stop_benchmark
    ;;
  -h|--help|help|"")
    usage
    ;;
  *)
    echo "Unknown command: $cmd" >&2
    usage
    exit 1
    ;;
esac
