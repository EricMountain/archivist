#!/usr/bin/env bash
# Reusable DynamoDB Local + S3-emulator fixture for the DynamoDB-Local/MinIO-gated test
# suites (test/lambda/*, test/repo/*) — see their own "Same DynamoDB Local + MinIO
# gate" doc comments. The S3 emulator was MinIO until its community image left
# Docker Hub (2026); it's now RustFS, a MinIO-compatible server. The tests' "MinIO"
# wording means "the local S3 emulator". Every session before this one span these up by hand and tore
# them down after ("no committed compose file for this exists" — see STATUS.md's
# history on plan step 1.8/2.10); this is that setup, made reusable instead of
# reinvented per session.
#
# In-memory/ephemeral on purpose: `down` (or a container restart) discards all data.
# These are throwaway fixtures for a test run, never anything worth persisting.
#
# Usage:
#   tools/local-infra.sh up       # idempotent — safe to call every time before testing
#   tools/local-infra.sh down     # stop and remove both containers
#   tools/local-infra.sh status   # show whether they're running
#   tools/local-infra.sh env      # print `export FOO=bar` lines for eval "$(...)"
#
# `make test-integration` wraps `up` + the gated `npm run test` in one step; `up`/
# `down`/`status` are exposed separately for iterative local work (leave the
# containers running across multiple test runs instead of paying startup cost every
# time — that's the whole point of "reusable").
#
# Both images are pinned by digest so CI and laptops run the same bytes; bump them by
# hand (Dependabot doesn't read shell scripts).
set -euo pipefail

DYNAMO_CONTAINER=archivist-test-dynamodb
S3_CONTAINER=archivist-test-s3
# Pre-RustFS name; removed on `up`/`down` so it can't hold the port.
LEGACY_MINIO_CONTAINER=archivist-test-minio
DYNAMO_IMAGE=amazon/dynamodb-local:3.3.2@sha256:6ada631ec7aa5fc83446855eb17233e3dad184fec47477e8140c6c569cfe0c86
S3_IMAGE=rustfs/rustfs:1.0.7@sha256:69bd3c4d8d79b74723bfd68c9fbd9085155d4d4b44a5a136d87502f3075cc68c
DYNAMO_PORT=18000
S3_PORT=19000
TABLE_NAME=archivist-test-media
ORIGINALS_BUCKET=archivist-test-originals
DERIVED_BUCKET=archivist-test-derived
S3_USER=testadmin
S3_PASSWORD=testadmin-secret
AWS_REGION_VALUE=us-east-1

export_env() {
  cat <<EOF
export DYNAMODB_ENDPOINT=http://localhost:${DYNAMO_PORT}
export MEDIA_TABLE=${TABLE_NAME}
export S3_ENDPOINT=http://localhost:${S3_PORT}
export ORIGINALS_BUCKET=${ORIGINALS_BUCKET}
export DERIVED_BUCKET=${DERIVED_BUCKET}
export AWS_ACCESS_KEY_ID=${S3_USER}
export AWS_SECRET_ACCESS_KEY=${S3_PASSWORD}
export AWS_REGION=${AWS_REGION_VALUE}
EOF
}

is_running() {
  docker ps --filter "name=^/${1}\$" --filter "status=running" --format '{{.Names}}' | grep -qx "$1"
}

exists() {
  docker ps -a --filter "name=^/${1}\$" --format '{{.Names}}' | grep -qx "$1"
}

wait_for() {
  local desc="$1" cmd="$2" tries=30
  until eval "$cmd" >/dev/null 2>&1; do
    tries=$((tries - 1))
    if [ "$tries" -le 0 ]; then
      echo "timed out waiting for $desc" >&2
      exit 1
    fi
    sleep 1
  done
}

up() {
  if is_running "$DYNAMO_CONTAINER"; then
    echo "$DYNAMO_CONTAINER already running"
  elif exists "$DYNAMO_CONTAINER"; then
    docker start "$DYNAMO_CONTAINER" >/dev/null
    echo "started existing $DYNAMO_CONTAINER"
  else
    docker run -d --name "$DYNAMO_CONTAINER" -p "${DYNAMO_PORT}:8000" \
      "$DYNAMO_IMAGE" -jar DynamoDBLocal.jar -inMemory -sharedDb >/dev/null
    echo "created $DYNAMO_CONTAINER"
  fi

  if exists "$LEGACY_MINIO_CONTAINER"; then
    docker rm -f "$LEGACY_MINIO_CONTAINER" >/dev/null
    echo "removed legacy $LEGACY_MINIO_CONTAINER"
  fi
  if is_running "$S3_CONTAINER"; then
    echo "$S3_CONTAINER already running"
  elif exists "$S3_CONTAINER"; then
    docker start "$S3_CONTAINER" >/dev/null
    echo "started existing $S3_CONTAINER"
  else
    docker run -d --name "$S3_CONTAINER" -p "${S3_PORT}:9000" \
      -e RUSTFS_ACCESS_KEY="$S3_USER" -e RUSTFS_SECRET_KEY="$S3_PASSWORD" \
      "$S3_IMAGE" >/dev/null
    echo "created $S3_CONTAINER"
  fi

  eval "$(export_env)"

  wait_for "DynamoDB Local" "aws dynamodb list-tables --endpoint-url http://localhost:${DYNAMO_PORT}"
  wait_for "RustFS" "aws --endpoint-url http://localhost:${S3_PORT} s3 ls"

  # Both the table and the buckets are idempotent to (re-)create: a fresh container
  # has neither, a reused one (docker start on an existing container, not a brand new
  # `docker run`) still has whatever a previous `up` already created, in-memory data
  # notwithstanding — DynamoDB Local's schema itself isn't in-memory-only, only its
  # items are, so re-describing before creating avoids a noisy "already exists" error.
  #
  # A table left over from an older version of this script, with a stale
  # timeline_gsi projection, is dropped and recreated: a GSI's projection can't be
  # altered in place, and the data is throwaway anyway. `place` is the newest
  # projected attribute, so its presence is the check.
  if aws dynamodb describe-table --table-name "$TABLE_NAME" --endpoint-url "http://localhost:${DYNAMO_PORT}" >/dev/null 2>&1; then
    projected=$(aws dynamodb describe-table --table-name "$TABLE_NAME" --endpoint-url "http://localhost:${DYNAMO_PORT}" \
      --query "Table.GlobalSecondaryIndexes[?IndexName=='timeline_gsi'].Projection.NonKeyAttributes[]" --output text)
    if ! printf '%s\n' $projected | grep -qx place; then
      aws dynamodb delete-table --table-name "$TABLE_NAME" --endpoint-url "http://localhost:${DYNAMO_PORT}" >/dev/null
      echo "dropped $TABLE_NAME (stale timeline_gsi projection)"
    fi
  fi
  if ! aws dynamodb describe-table --table-name "$TABLE_NAME" --endpoint-url "http://localhost:${DYNAMO_PORT}" >/dev/null 2>&1; then
    # Mirrors terraform/dynamodb.tf: pk/sk keys, timeline_gsi + facet_gsi, same
    # projections (terraform/locals.tf's timeline_projection and grid_projection). Keep
    # these in sync by hand — there's no single source both Terraform and this script
    # can read from.
    aws dynamodb create-table \
      --endpoint-url "http://localhost:${DYNAMO_PORT}" \
      --table-name "$TABLE_NAME" \
      --attribute-definitions \
        AttributeName=pk,AttributeType=S AttributeName=sk,AttributeType=S \
        AttributeName=timelinePk,AttributeType=S AttributeName=timelineSk,AttributeType=S \
        AttributeName=facetPk,AttributeType=S AttributeName=facetSk,AttributeType=S \
      --key-schema AttributeName=pk,KeyType=HASH AttributeName=sk,KeyType=RANGE \
      --billing-mode PAY_PER_REQUEST \
      --global-secondary-indexes \
        '[{"IndexName":"timeline_gsi","KeySchema":[{"AttributeName":"timelinePk","KeyType":"HASH"},{"AttributeName":"timelineSk","KeyType":"RANGE"}],"Projection":{"ProjectionType":"INCLUDE","NonKeyAttributes":["thumbs","encDek","encKeyId","width","height","mime","tzOffsetMin","status","preview","place"]}},{"IndexName":"facet_gsi","KeySchema":[{"AttributeName":"facetPk","KeyType":"HASH"},{"AttributeName":"facetSk","KeyType":"RANGE"}],"Projection":{"ProjectionType":"INCLUDE","NonKeyAttributes":["thumbs","encDek","encKeyId","width","height","mime","tzOffsetMin","status"]}}]' \
      >/dev/null
    echo "created table $TABLE_NAME"
  fi

  for bucket in "$ORIGINALS_BUCKET" "$DERIVED_BUCKET"; do
    if ! aws --endpoint-url "http://localhost:${S3_PORT}" s3 ls "s3://${bucket}" >/dev/null 2>&1; then
      aws --endpoint-url "http://localhost:${S3_PORT}" s3 mb "s3://${bucket}" >/dev/null
      echo "created bucket $bucket"
    fi
  done

  echo "ready — eval \"\$(tools/local-infra.sh env)\" to export the env vars into your shell"
}

down() {
  docker rm -f "$DYNAMO_CONTAINER" "$S3_CONTAINER" "$LEGACY_MINIO_CONTAINER" >/dev/null 2>&1 || true
  echo "removed $DYNAMO_CONTAINER and $S3_CONTAINER"
}

status() {
  for c in "$DYNAMO_CONTAINER" "$S3_CONTAINER"; do
    if is_running "$c"; then
      echo "$c: running"
    elif exists "$c"; then
      echo "$c: stopped"
    else
      echo "$c: absent"
    fi
  done
}

case "${1:-}" in
  up) up ;;
  down) down ;;
  status) status ;;
  env) export_env ;;
  *)
    echo "usage: $0 {up|down|status|env}" >&2
    exit 1
    ;;
esac
