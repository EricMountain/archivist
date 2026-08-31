.PHONY: install build test typecheck deploy deploy-dev plan-dev clean \
	test-infra-up test-infra-down test-infra-status test-integration

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
