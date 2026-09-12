.PHONY: install build test typecheck deploy deploy-dev plan-dev clean \
	test-infra-up test-infra-down test-infra-status test-integration \
	teardown-load-test-dev backfill-histogram backfill-histogram-dev

install:
	npm install

build: install
	npm run build

# Pure-unit-test suite only — no DynamoDB Local/MinIO needed, and every test that
# does need them (test/lambda/*, test/repo/*) skips itself via its own RUN check when
# their env vars aren't set. See test-integration for the full suite.
test: install
	npm run typecheck
	npm run test

typecheck: install
	npm run typecheck

# tools/local-infra.sh's own doc comment has the full account — idempotent, reusable
# across runs rather than spun up and torn down per session.
test-infra-up:
	./tools/local-infra.sh up

test-infra-down:
	./tools/local-infra.sh down

test-infra-status:
	./tools/local-infra.sh status

# The full suite, DynamoDB-Local/MinIO-gated tests included. Leaves the containers
# running afterwards (test-infra-down to reclaim them) — rerunning this is meant to be
# cheap, not a fresh docker pull/boot every time.
test-integration: install test-infra-up
	@eval "$$(./tools/local-infra.sh env)" && npm run typecheck && npm run test

# Terraform must never be run against a stale build. This is the only supported
# entry point for a deploy — never `terraform apply` on its own.
# Explicitly selects the "default" workspace (prod's state) so a deploy never lands in
# whatever workspace a previous dev session left checked out.
deploy: build
	cd terraform && terraform workspace select default && terraform apply

# The dev instance shares prod's backend.hcl (same state bucket) but lives in its own
# Terraform workspace, so it gets its own state file, and its own tfvars, so it gets its
# own domain/environment name — see private/instance/dev.tfvars and terraform/README.md.
plan-dev: build
	cd terraform && terraform workspace select dev && terraform plan -var-file=../private/instance/dev.tfvars

deploy-dev: build
	cd terraform && terraform workspace select dev && terraform apply -var-file=../private/instance/dev.tfvars

clean:
	rm -rf dist coverage

# Hard-deletes exactly the synthetic photos tools/teardown-load-test.mjs's own doc
# describes (default stem prefix "load_test_") from the dev instance's real S3 +
# DynamoDB -- no soft delete, no HASH# tombstone, just gone. Defaults to a dry run;
# pass EXECUTE=1 to actually delete. OWNER_ID is required and deliberately not
# defaulted -- this is real-account data, not something to guess at. No plain
# `teardown-load-test` (prod) counterpart: load-test data is synthetic, seeded only
# against dev by the Android instrumented test that makes it, so there's nothing for
# a prod target to ever clean up. Named with the trailing `-dev` anyway, matching
# every other workspace-scoped target here (deploy/deploy-dev,
# backfill-histogram/-dev) rather than burying it mid-name.
PREFIX ?= load_test_

teardown-load-test-dev:
	@test -n "$(OWNER_ID)" || (echo "usage: make teardown-load-test-dev OWNER_ID=<ownerId> [PREFIX=load_test_] [EXECUTE=1]"; exit 1)
	cd terraform && terraform workspace select dev >/dev/null && \
	MEDIA_TABLE=$$(terraform output -raw media_table_name) \
	ORIGINALS_BUCKET=$$(terraform output -raw originals_bucket) \
	DERIVED_BUCKET=$$(terraform output -raw derived_bucket) \
	node ../tools/teardown-load-test.mjs --owner-id "$(OWNER_ID)" --prefix "$(PREFIX)" $(if $(EXECUTE),--yes)

# Rebuilds the per-day photo histogram (design.md pattern 15) from timeline_gsi —
# needed once per owner whose photos predate this feature, since the counters only
# ever move on a create/trash/restore and nothing already in the table was ever
# counted. Safe to re-run: it overwrites rather than adds, so running it twice
# produces the same state. Defaults to a dry run (reports the counts, writes
# nothing); pass EXECUTE=1 to write. Either OWNER_ID=<ownerId> or ALL=1 (every owner
# in the registry) is required.
#
# backfill-histogram (prod, "default" workspace) and backfill-histogram-dev differ
# only in which workspace supplies MEDIA_TABLE -- no -var-file either way, since
# `terraform output` reads already-applied state rather than re-evaluating
# variables (the same reason `terraform output` calls elsewhere in this file, e.g.
# teardown-load-test-dev above, never carry one). Shared here via one `define`/
# `call` rather than copy-pasted twice, unlike deploy/deploy-dev/plan-dev below,
# which differ in `-var-file` too and stay direct recipes deliberately -- those are
# the actual deploy-to-AWS entry points, and directly-readable beats DRY on a path
# where "which exact command runs" needs to be obvious at a glance.
define backfill_histogram_recipe
	@test -n "$(OWNER_ID)$(ALL)" || (echo "usage: make $(1) OWNER_ID=<ownerId> [EXECUTE=1]  (or ALL=1 for every owner)"; exit 1)
	cd terraform && terraform workspace select $(2) >/dev/null && \
	MEDIA_TABLE=$$(terraform output -raw media_table_name) \
	node ../tools/backfill-histogram.mjs $(if $(ALL),--all-owners,--owner-id "$(OWNER_ID)") $(if $(EXECUTE),--yes)
endef

backfill-histogram:
	$(call backfill_histogram_recipe,backfill-histogram,default)

backfill-histogram-dev:
	$(call backfill_histogram_recipe,backfill-histogram-dev,dev)
