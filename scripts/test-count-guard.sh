#!/usr/bin/env bash
# scripts/test-count-guard.sh — fail if the @Test count dropped vs the PR base (ARCHITECTURE §10:
# "CI fails if the @Test count drops"). Protects against tests being deleted or weakened.
# In CI, set BASE_SHA=${{ github.event.pull_request.base.sha }} and use a full checkout (fetch-depth 0).
set -uo pipefail
cd "$(dirname "$0")/.."
mkdir -p build

count_fs() { grep -rhoE '@Test' --include='*.kt' app core detector 2>/dev/null | wc -l | tr -d ' '; }
count_ref() { git grep -cE '@Test' "$1" -- app core detector 2>/dev/null | awk -F: '{s+=$NF} END{print s+0}'; }

CURRENT="$(count_fs)"
echo "$CURRENT" > build/test-count.txt
echo "Current @Test count: $CURRENT"

if [ -n "${BASE_SHA:-}" ]; then
  BASE="$(count_ref "$BASE_SHA")"
  echo "Base ($BASE_SHA) @Test count: ${BASE:-0}"
  if [ "${BASE:-0}" -gt 0 ] && [ "$CURRENT" -lt "${BASE:-0}" ]; then
    echo "::error::@Test count dropped from $BASE to $CURRENT. Tests must not be removed or weakened (AGENTS.md)."
    exit 1
  fi
fi
echo "OK"
