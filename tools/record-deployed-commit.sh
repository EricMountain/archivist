#!/bin/sh
# Records which commit is live on an instance (plan 03, step 3.12). `make deploy` /
# `make deploy-dev` run this from terraform/ right after a successful apply, with the
# workspace they applied still selected and the operator's own credentials, like the
# apply. The CI deploy (step 3.21) reads the same parameter to decide what to ship.
#
# Writes `git rev-parse HEAD`, with `-dirty` appended if the tree has any change
# (`git status --porcelain` non-empty), to the SSM parameter named by the
# `deployed_commit_parameter` output. Skips quietly if that output is null: CI deploys
# aren't enabled for this instance (github_repository unset).
set -eu

# A null output isn't stored in state at all, so `terraform output <name>` would fail
# with "not found"; read the whole map instead and treat absent as null. Any other
# failure (state unreadable, say) still fails the deploy loudly.
outputs=$(terraform output -json)
param=$(printf '%s' "$outputs" | python3 -I -c '
import json, sys
v = json.load(sys.stdin).get("deployed_commit_parameter", {}).get("value")
print(v or "")')
if [ -z "$param" ]; then
  exit 0
fi
region=$(printf '%s' "$outputs" | python3 -I -c '
import json, sys
print(json.load(sys.stdin)["region"]["value"])')

commit=$(git rev-parse HEAD)
if [ -n "$(git status --porcelain)" ]; then
  commit="$commit-dirty"
fi

aws ssm put-parameter --region "$region" --name "$param" --type String \
  --value "$commit" --overwrite >/dev/null
echo "Recorded deployed commit $commit in $param"
