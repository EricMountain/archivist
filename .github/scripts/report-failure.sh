#!/usr/bin/env bash
# Plan step 3.22 (docs/plans/03-automated-updates.md): an unattended run failed, so
# open an issue labelled automation-failure, or comment on the one already open. The
# run was triggered by the automerge App or the scheduler, so GitHub's own failure
# email may reach no one.
#
# Needs GH_TOKEN with issues: write, and GITHUB_REPOSITORY / GITHUB_SERVER_URL /
# GITHUB_RUN_ID / GITHUB_WORKFLOW / GITHUB_SHA (all set by Actions).
set -euo pipefail

label=automation-failure
repo=$GITHUB_REPOSITORY
run_url="${GITHUB_SERVER_URL}/${repo}/actions/runs/${GITHUB_RUN_ID}"
body="**${GITHUB_WORKFLOW}** failed on \`${GITHUB_SHA}\`: ${run_url}"

# Idempotent: fails harmlessly when the label already exists.
gh label create "$label" --repo "$repo" --color B60205 \
  --description "An unattended workflow run failed" >/dev/null 2>&1 || true

open=$(gh issue list --repo "$repo" --label "$label" --state open --limit 1 \
  --json number --jq '.[0].number // empty')
if [ -n "$open" ]; then
  gh issue comment "$open" --repo "$repo" --body "$body"
else
  gh issue create --repo "$repo" --label "$label" \
    --title "Automation failure" \
    --body "$body

Comments on this issue record later failures. Close it once they're dealt with; the
next failure opens a new one."
fi
