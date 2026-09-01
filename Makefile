.PHONY: install build test typecheck deploy deploy-dev plan-dev clean \
	test-infra-up test-infra-down test-infra-status test-integration \
	teardown-dev-load-test

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
# defaulted -- this is real-account data, not something to guess at.
PREFIX ?= load_test_

teardown-dev-load-test:
	@test -n "$(OWNER_ID)" || (echo "usage: make teardown-dev-load-test OWNER_ID=<ownerId> [PREFIX=load_test_] [EXECUTE=1]"; exit 1)
	cd terraform && terraform workspace select dev >/dev/null && \
	MEDIA_TABLE=$$(terraform output -raw media_table_name) \
	ORIGINALS_BUCKET=$$(terraform output -raw originals_bucket) \
	DERIVED_BUCKET=$$(terraform output -raw derived_bucket) \
	node ../tools/teardown-load-test.mjs --owner-id "$(OWNER_ID)" --prefix "$(PREFIX)" $(if $(EXECUTE),--yes)
