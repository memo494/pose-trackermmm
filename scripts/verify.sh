#!/usr/bin/env bash
# scripts/verify.sh — the local build + test gate (ARCHITECTURE §10, docs/AI_TEAM.md §6).
# Runs the same checks as CI (spotlessCheck test lint assembleDebug), writes the full log to
# build/verify.log, and prints only a short summary. Non-zero exit on failure.
#
#   bash scripts/verify.sh
#
set -uo pipefail
cd "$(dirname "$0")/.."
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
mkdir -p build
LOG="build/verify.log"
: > "$LOG"

TASKS=(spotlessCheck test lint assembleDebug)

# Retry transient proxy 429s (Maven Central rate-limiting in cloud sessions) with backoff.
run_gradle() {
  local attempt=1 max=4
  while :; do
    if ./gradlew "$@" --console=plain >>"$LOG" 2>&1; then
      return 0
    fi
    if grep -qE '429.*Too Many Requests' "$LOG" && [ "$attempt" -lt "$max" ]; then
      echo "  (transient 429 from Maven Central; retry $attempt/$max)"
      sleep $((attempt * 15))
      attempt=$((attempt + 1))
      continue
    fi
    return 1
  done
}

echo "==> ./gradlew ${TASKS[*]}   (full log: $LOG)"
if run_gradle "${TASKS[@]}"; then
  echo "PASS: ${TASKS[*]}"
  apk="$(ls -1 app/build/outputs/apk/debug/*.apk 2>/dev/null | head -1 || true)"
  [ -n "$apk" ] && echo "APK:  $apk"
  # @Test count guard helper: report the count so callers can compare across commits.
  tcount="$(grep -rhoE '@Test' --include='*.kt' app core detector 2>/dev/null | wc -l | tr -d ' ')"
  echo "@Test count: $tcount"
  exit 0
fi

echo "FAIL — summary from $LOG:"
echo "--- failing Gradle tasks ---"
grep -E '> Task .* FAILED' "$LOG" | sort -u | head
echo "--- Kotlin compile errors (first 20) ---"
grep -E '^e: ' "$LOG" | head -20
echo "--- failing tests (first 15) ---"
grep -iE 'FAILED|there were failing tests|> .*Test' "$LOG" | head -15
echo "--- lint (top 5) ---"
grep -iE 'lint.*error|[0-9]+ error[s]? ' "$LOG" | head -5
echo "(open $LOG by line range for the first failure's context)"
exit 1
