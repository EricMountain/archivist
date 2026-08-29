.PHONY: install build test typecheck deploy deploy-dev plan-dev clean

install:
	npm install

build: install
	npm run build

test: install
	npm run typecheck
	npm run test

typecheck: install
	npm run typecheck

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
