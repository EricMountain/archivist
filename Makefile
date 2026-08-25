.PHONY: install build test typecheck deploy clean

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
deploy: build
	cd terraform && terraform apply

clean:
	rm -rf dist coverage
