#!/usr/bin/env bash
# Plan step 3.21 (docs/plans/03-automated-updates.md): deploy the Lambda code at $SHA
# to one environment, called from backend-ci.yml's deploy-dev / deploy-prod jobs once
# AWS credentials for that environment's CI deploy role are in the environment.
#
# Deploys everything between the live commit (the SSM marker that `make deploy` and
# this script both write) and $SHA, but only the functions whose bundle bytes changed.
# Never touches infrastructure: if terraform/ differs between the two, it skips with a
# warning, because `make deploy` has to apply that first (D2/D3).
#
# Required env: SHA (full commit), NAME_PREFIX (archivist, archivist-dev, ...),
# INSTANCE_DOMAIN (for the smoke test), DEPLOY_ENV (only for messages).
# Writes `result=<deployed|unchanged|up-to-date|infra-pending>` to $GITHUB_OUTPUT when
# that is set.
set -euo pipefail

: "${SHA:?}" "${NAME_PREFIX:?}" "${INSTANCE_DOMAIN:?}" "${DEPLOY_ENV:?}"

# Exactly the functions the CI deploy role may update (terraform/ci.tf). A new Lambda
# arrives with a Terraform change, so the infrastructure guard covers it.
FUNCTIONS="api s3event purge"
PARAM="/${NAME_PREFIX}/deployed-commit"

result() {
  echo "Result: $1"
  if [ -n "${GITHUB_OUTPUT:-}" ]; then echo "result=$1" >> "$GITHUB_OUTPUT"; fi
}

fail() {
  echo "::error::$DEPLOY_ENV: $1"
  exit 1
}

# 1. What's live? CI won't guess.
if ! marker=$(aws ssm get-parameter --name "$PARAM" --query Parameter.Value --output text 2>"${RUNNER_TEMP:-/tmp}/ssm.err"); then
  if grep -q ParameterNotFound "${RUNNER_TEMP:-/tmp}/ssm.err"; then
    marker=""
  else
    cat "${RUNNER_TEMP:-/tmp}/ssm.err" >&2
    fail "couldn't read $PARAM."
  fi
fi
case "$marker" in
  ""|None|unknown|*-dirty)
    fail "the deployed-commit marker is '${marker:-missing}'. Deploy once with \`make deploy$([ "$DEPLOY_ENV" = prod ] || echo "-$DEPLOY_ENV")\` from a clean tree." ;;
esac
if ! printf '%s' "$marker" | grep -qxE '[0-9a-f]{40}'; then
  fail "the deployed-commit marker '$marker' isn't a commit SHA."
fi
if [ "$marker" = "$SHA" ]; then
  echo "$DEPLOY_ENV already runs $SHA."
  result up-to-date
  exit 0
fi
if ! git cat-file -e "${marker}^{commit}" 2>/dev/null; then
  fail "the live commit $marker isn't in this repository's history."
fi
# A newer commit's deploy can run first: the per-environment concurrency group keeps
# whichever run was queued last, not the newest commit. Deploying an ancestor of the
# live commit would roll it back, so stop. (A marker merely beside SHA, from a
# `make deploy` elsewhere, still deploys.)
if git merge-base --is-ancestor "$SHA" "$marker"; then
  echo "$DEPLOY_ENV already runs $marker, which includes $SHA. Nothing to do."
  result superseded
  exit 0
fi
echo "$DEPLOY_ENV runs $marker; deploying $SHA."

# 2. Infrastructure guard. git diff rather than git log, so it also works when the
#    marker is ahead of, or beside, main (a `make deploy` from another checkout).
infra=$(git diff --name-only "$marker" "$SHA" -- terraform/)
if [ -n "$infra" ]; then
  echo "$infra"
  echo "::warning::$DEPLOY_ENV: infrastructure changes pending; run \`make deploy\`. Lambda deploy skipped."
  result infra-pending
  exit 0
fi

# 3. Build both commits, each with its own lockfile, and compare bundles.
work=$(mktemp -d "${RUNNER_TEMP:-/tmp}/deploy.XXXXXX")
cleanup() {
  git worktree remove --force "$work/live" >/dev/null 2>&1 || true
  git worktree remove --force "$work/new" >/dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT
for side in live new; do
  if [ "$side" = live ]; then rev=$marker; else rev=$SHA; fi
  git worktree add --detach "$work/$side" "$rev" >/dev/null
  echo "::group::Build $side ($rev)"
  (cd "$work/$side" && npm ci --no-audit --no-fund && npm run build)
  echo "::endgroup::"
done

changed=""
for f in $FUNCTIONS; do
  [ -f "$work/new/dist/$f/index.mjs" ] || fail "no bundle for $f at $SHA."
  if [ -d "$work/live/dist/$f" ] && diff -r -q "$work/live/dist/$f" "$work/new/dist/$f" >/dev/null; then
    echo "$f: unchanged"
  else
    echo "$f: changed"
    changed="$changed $f"
  fi
done

if [ -z "$changed" ]; then
  aws ssm put-parameter --name "$PARAM" --value "$SHA" --type String --overwrite >/dev/null
  echo "No bundle changed; marker moved to $SHA."
  result unchanged
  exit 0
fi

# 4. Update each changed function the way data.archive_file packages it: the bundle
#    directory's contents (index.mjs and its sourcemap) at the zip root.
for f in $changed; do
  zip="$work/$f.zip"
  (cd "$work/new/dist/$f" && zip -q -X -r "$zip" .)
  echo "Updating ${NAME_PREFIX}-$f"
  aws lambda update-function-code --function-name "${NAME_PREFIX}-$f" \
    --zip-file "fileb://$zip" --query '[FunctionName,CodeSha256]' --output text
  aws lambda wait function-updated-v2 --function-name "${NAME_PREFIX}-$f"
done

# 5. Smoke test. The API's module load is exercised by /api/health; s3event and purge
#    only run on events, so for them only LastUpdateStatus is checked (a known gap, see
#    the plan's open questions).
ok=""
for _ in $(seq 1 10); do
  if curl -fsS --max-time 10 "https://${INSTANCE_DOMAIN}/api/health"; then
    ok=1
    echo
    break
  fi
  sleep 3
done
[ -n "$ok" ] || fail "smoke test failed: /api/health didn't answer 200 within ~30s. The marker wasn't moved."

for f in $changed; do
  status=$(aws lambda get-function-configuration --function-name "${NAME_PREFIX}-$f" \
    --query LastUpdateStatus --output text)
  [ "$status" = Successful ] || fail "${NAME_PREFIX}-$f LastUpdateStatus is $status. The marker wasn't moved."
done

# 6. Only now record what's live. A failed run leaves the marker where it was, so a
#    re-run (or the next merge) redeploys the same range.
aws ssm put-parameter --name "$PARAM" --value "$SHA" --type String --overwrite >/dev/null
echo "Deployed$changed to $DEPLOY_ENV; marker moved to $SHA."
result deployed
