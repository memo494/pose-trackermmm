#!/usr/bin/env bash
# scripts/ci-status.sh — poll GitHub Actions for the current branch until the latest run completes
# (docs/AI_TEAM.md §6). Prints the conclusion, failed job/step names, and the tail of failed logs.
# REST API via `gh` (no GraphQL). Max wait 20 min.
#
#   bash scripts/ci-status.sh
#
set -uo pipefail
cd "$(dirname "$0")/.."

BRANCH="$(git rev-parse --abbrev-ref HEAD)"
ORIGIN="$(git config --get remote.origin.url)"
SLUG="$(printf '%s' "$ORIGIN" | sed -E 's#(git@github.com:|https://github.com/)##; s#\.git$##')"
echo "Repo: $SLUG   Branch: $BRANCH"

DEADLINE=$(( $(date +%s) + 1200 ))
RUN_ID=""; STATUS=""; CONCL=""
while :; do
  read -r RUN_ID STATUS CONCL < <(
    gh api "repos/$SLUG/actions/runs?branch=$BRANCH&per_page=1" \
      -q '.workflow_runs[0] | "\(.id) \(.status) \(.conclusion)"' 2>/dev/null
  ) || true
  if [ -z "${RUN_ID:-}" ] || [ "$RUN_ID" = "null" ]; then
    echo "$(date +%H:%M:%S) no run yet for $BRANCH"
  else
    echo "$(date +%H:%M:%S) run=$RUN_ID status=${STATUS:-?} conclusion=${CONCL:-?}"
    [ "${STATUS:-}" = "completed" ] && break
  fi
  if [ "$(date +%s)" -ge "$DEADLINE" ]; then
    echo "Timed out after 20 min."
    exit 2
  fi
  sleep 20
done

if [ "$CONCL" = "success" ]; then
  echo "CI GREEN ✓"
  exit 0
fi

echo "CI $CONCL ✗ — failed jobs/steps:"
gh api "repos/$SLUG/actions/runs/$RUN_ID/jobs" \
  -q '.jobs[] | select(.conclusion=="failure") | "  job: \(.name)",( .steps[] | select(.conclusion=="failure") | "    step: \(.name)")' \
  2>/dev/null || true
echo "--- failed step logs (tail 40) ---"
gh run view "$RUN_ID" --repo "$SLUG" --log-failed 2>/dev/null | tail -40 || true
exit 1
