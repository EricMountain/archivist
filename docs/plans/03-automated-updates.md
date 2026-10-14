# Plan 03 — Automated dependency updates and delivery

**Goal.** Dependabot opens dependency PRs, CI gates them, patch/minor ones merge on
their own, and the result reaches users without anyone running a command: updated
Lambdas redeployed to `dev` then `prod`, and an updated app on the Play internal track.

**Depends on** plan 01 deployed (both workspaces) and plan 02 step 2.16's release path
working. The second already holds: `android-v*.*.*` tags have been publishing to the
internal track since 2026-09.

**Scope.** This is automation for *one operator's* instance: the author's. Every piece
is opt-in, so a fork or another operator's clone does nothing until they configure it.
It never makes anything deployment-specific a committed value; see "Nothing personal
in the committed tree" in `CLAUDE.md`. Workflow logs on a public repo are public, so the
account ID, role ARN and domain go in as **secrets** (which Actions masks in logs), not
variables.

**Why Dependabot rather than Renovate.** The deciding reason is that Dependabot is built
into GitHub: no third-party app gets write access to a public repo whose workflows hold
AWS and Play credentials. Its runs also can't read Actions secrets at all. What we give
up, and how this plan makes up for it:

- Automerge isn't built in. It's a short workflow (3.5), plus a small GitHub App so
  that automerged PRs start workflows on `main` (D4, A6).
- No automatic pinning of actions to exact commits. Step 3.0 pins every action by
  hand, A10 makes pinning mandatory for the repo, and Dependabot then keeps the pins
  current.
- No lockfile maintenance. Transitive dependencies only move with a security update or
  a direct parent's bump. That's acceptable for this repo.

`renovate.json` was never acted on (no Renovate PR was ever opened) and is deleted in
3.5.

---

## What exists today

Read this before changing anything. Every item was checked on 2026-10-14.

| Piece | State |
| --- | --- |
| `renovate.json` | Exists but inert: the Renovate app was never installed. Removed in 3.5. |
| `.github/dependabot.yml` | Doesn't exist. |
| `android-ci.yml` | Runs on PRs and `main`, but is `paths`-filtered, so it **reports no status at all** on a PR that doesn't touch `android/**`. That breaks it as a required check (see 3.4). |
| `android-release.yml` | Triggered by a tag. Works: `android-v0.3.16` published on 2026-10-13. Its five secrets are **repository** secrets. |
| Backend CI | **None.** Nothing tests `src/**` on a PR. |
| Branch protection on `main` | **None, and no rulesets at all.** Direct and force pushes are allowed. The repo allows auto-merge, but nothing is required, so an auto-merge would go through as soon as no check is *failing*. Don't land 3.5's automerge workflow before A9 is complete. All three merge methods are enabled. |
| Action pinning | Every `uses:` is a moving tag (`actions/checkout@v4` and so on). `sha_pinning_required` is `false`. |
| GitHub Releases | **None.** Android versions exist only as `android-v*` tags, which anyone with write access can move or delete. |
| Lambda deploy | Only `make deploy` / `make deploy-dev`, from a laptop. That runs a full `terraform apply` with the operator's own credentials. |

---

## Decisions this plan makes (change them before starting, not halfway through)

**D1 — Android goes to the internal track only.** Publishing a dependency bump straight
to production with no human looking at it is the one step here that can break every
installed copy at once. Automation stops at internal, the same track the tag path
already uses. Promoting to production stays a click in the Play Console. If that later
feels like friction, add a `promote` job behind a manual `workflow_dispatch`, or use a
staged rollout, rather than fully automatic promotion.

**D2 — Whatever is on `main` ships.** Nothing checks who wrote a commit. Every merge to
`main` that changes a Lambda bundle is deployed (D4). If `android/**` changed
since the last `android-v*` tag, the weekly run releases the app. Human work and
Dependabot work are treated the same. Two consequences:

- **`main` must always be releasable.** A9 enforces this: every change reaches `main`
  through a PR whose checks passed, and nobody can push or force-push past them, not
  even the author. The author pushes directly to `main` today, so this is a change in
  habit, made deliberately. On the backend, merging a PR is now a production deploy a
  few minutes later.
- **Infrastructure is the one thing CI won't ship.** Because of D3, CI can deploy code
  but can't apply Terraform. So if `terraform/**` differs between what's live and what's
  about to ship, the Lambda deploy **skips** with a warning: "infrastructure changes
  pending; run `make deploy`". It doesn't ship code ahead of the infrastructure that
  code may need. `make deploy` applies both and moves the marker (3.2), so the next
  merge deploys normally. This is a skip rather than a failure because it's the
  expected result of every Terraform commit, not an anomaly. It's also the only policy
  needed for code that depends on infrastructure: a change that needs both normally
  touches both in the same commit or push.

**D3 — CI updates Lambda code; it does not run `terraform apply`.** A dependency bump
changes bundle bytes and nothing else. CI gets a role that can only call
`lambda:UpdateFunctionCode` on the three `archivist[-env]-*` functions and write one SSM
parameter. A full `terraform apply` from CI would need close to admin rights (IAM,
Cognito, CloudFront), a copy of the private tfvars and access to the state bucket. That
is a large blast radius for a public repo, given the goal. Infrastructure changes,
Dependabot's Terraform provider bumps included, stay with `make deploy`. **Consequence:**
after a CI deploy, the next `make deploy` shows an in-place update to each Lambda it
touched, because Terraform's recorded `source_code_hash` is stale. It re-uploads the same
bytes and is harmless. This is expected drift; don't "fix" it.

**D4 — Cadence: Lambdas deploy on every merge; the app is released weekly.**

- **Lambdas.** A merge to `main` runs `backend-ci`. If it passes, the same workflow
  deploys to `dev`, smoke tests, then deploys to `prod` (3.8). There's one path for
  everything, Dependabot's PRs and the author's own alike, and the rollback is a revert
  PR.
- **Android.** Every Play upload is a new `versionCode` and an update on every tester's
  phone, so the week's changes go out as one release on Sunday (3.7). A hand-pushed tag
  still releases at any time.

**Merges have to start workflows, which takes one GitHub App.** The automerge workflow
(3.5) can't enable auto-merge with the built-in `GITHUB_TOKEN`. GitHub doesn't start
workflows for events caused by that token, so an automerged Dependabot PR would land on
`main` without running CI or the deploy. The automerge workflow instead uses a token
from a small GitHub App that the author owns (A6), installed on this repo only. Events
caused by an App token *do* start workflows, so a Dependabot merge behaves exactly like
a human one. The alternative was a scheduled catch-up run, which meant two deploy paths
and calling CI as a reusable workflow. The App is one manual step and one stored
credential, and it removes all of that. The App's private key is a **Dependabot**
secret, which only Dependabot-triggered runs can read, and only the automerge workflow
references it.

---

## Part A — Steps you do by hand

These need console access, account-level rights or a judgement call. Each one says when
it's needed. **A1–A6, and the first half of A9, can all be done now.** The rest waits on
a code step.

### A1 — Lock down GitHub Actions defaults (now)

Settings → Actions → General:

- **Workflow permissions → "Read repository contents and packages permissions".** Each
  workflow already asks for what it needs in its `permissions:` block. This makes that
  the only way to get write access. The automerge workflow (3.5) asks for its write
  scopes explicitly, which GitHub honours for Dependabot-triggered runs too.
- **"Allow GitHub Actions to create and approve pull requests" → off.** Auto-merge
  doesn't need approval, because the A9 ruleset requires checks, not reviews.
- **Fork pull request workflows → "Require approval for all outside collaborators".**

Settings → General → Releases:

- **Enable release immutability.** Once a release is published, its assets can't be
  changed or deleted, and its tag can't be moved or deleted. This only affects releases
  published after it's turned on. There are none yet; 3.6 starts creating one for every
  Android version.

### A2 — Move the Play secrets into an environment (now)

The five Android release secrets are repository secrets today. Any workflow on any
same-repo branch can read a repository secret by naming it. Dependabot's own runs can't,
but a human-pushed branch can. Environment secrets are only released to a job that
targets that environment, and the environment can be limited to `main` and to release
tags.

```sh
gh api -X PUT repos/{owner}/{repo}/environments/play \
  -f 'deployment_branch_policy[protected_branches]=false' \
  -F 'deployment_branch_policy[custom_branch_policies]=true'
gh api -X POST repos/{owner}/{repo}/environments/play/deployment-branch-policies -f name=main -f type=branch
gh api -X POST repos/{owner}/{repo}/environments/play/deployment-branch-policies -f name='android-v*' -f type=tag
```

Then recreate each secret with `--env play`. `gh secret set` reads the value from stdin,
so nothing ends up in shell history. You need the original values: the keystore file
and the service-account JSON, kept somewhere durable per
`docs/ops/android-release-secrets.md`.

```sh
base64 -i release.keystore | gh secret set ANDROID_KEYSTORE_BASE64 --env play
gh secret set ANDROID_KEYSTORE_PASSWORD --env play
gh secret set ANDROID_KEY_ALIAS        --env play
gh secret set ANDROID_KEY_PASSWORD     --env play
gh secret set PLAY_SERVICE_ACCOUNT_JSON --env play < service-account.json
```

**Delete the repository-level copies only after 3.6 has shipped** (the workflow change
that adds `environment: play`). Until then the tag path still reads the repo-level ones.

### A3 — Create the GitHub OIDC identity provider in AWS (now)

It's account-global, one per account, so `prod` and `dev` share it. That's also why it
isn't in the Terraform root module: a second workspace would try to create it again.
Check first:

```sh
aws iam list-open-id-connect-providers   # look for token.actions.githubusercontent.com
aws iam create-open-id-connect-provider \
  --url https://token.actions.githubusercontent.com \
  --client-id-list sts.amazonaws.com
```

AWS no longer checks the thumbprint for this provider, so none is needed. This creates
no credentials. Nothing can assume anything through it until 3.1's role exists.

### A4 — Create the `dev` and `prod` GitHub environments (now)

Same shape as `play`: deployments only from `main`.

```sh
for env in dev prod; do
  gh api -X PUT repos/{owner}/{repo}/environments/$env \
    -f 'deployment_branch_policy[protected_branches]=false' \
    -F 'deployment_branch_policy[custom_branch_policies]=true'
  gh api -X POST repos/{owner}/{repo}/environments/$env/deployment-branch-policies -f name=main -f type=branch
done
```

**Don't add required reviewers to `prod`.** That turns automation back into a button.
The protection comes from `dev` deploying first and passing a smoke test (3.8). A wait
timer of a few minutes on `prod` is a reasonable middle ground if you want a window to
cancel.

### A5 — Turn on Dependabot alerts and security updates (now)

Settings → Code security:

- **Dependabot alerts → Enable.**
- **Dependabot security updates → Enable.** These PRs ignore `dependabot.yml`'s
  schedule and cooldown, which is what you want for a known vulnerability. Once 3.5
  lands they automerge like any other patch/minor update.
- **Grouped security updates → Enable**, so a batch of advisories arrives as one PR
  per ecosystem.

This is safe to do before anything else: security-update PRs just sit open until 3.5's
automerge workflow and A9's ruleset exist. *Version* updates don't start until
`.github/dependabot.yml` is committed (3.5).

### A6 — Create the automerge GitHub App (now)

This is what lets an automerged PR start workflows on `main` (D4). Go to Settings
(your account, not the repo) → Developer settings → GitHub Apps → New GitHub App:

- Name: anything unique, e.g. `<you>-archivist-automerge`. Homepage URL: the repo.
- **Webhook: uncheck "Active".** The App never receives events; it only mints tokens.
- Repository permissions, and nothing else:
  - **Contents: Read and write**, to merge.
  - **Pull requests: Read and write**, to enable auto-merge.
  - **Workflows: Read and write**. Without it, GitHub refuses to merge Dependabot's
    `github-actions` PRs, which edit files under `.github/workflows/`.
  - Metadata: Read-only (mandatory).
- "Where can this GitHub App be installed?" → **Only on this account**.

Then:

1. Generate a private key (it downloads a `.pem`) and note the App ID.
2. Install the App → **Only select repositories** → this repo.
3. Store both as **Dependabot** secrets, not Actions secrets. The automerge workflow
   runs on Dependabot's PRs, and those runs can only read Dependabot secrets:
   ```sh
   gh secret set AUTOMERGE_APP_ID --app dependabot          # paste the App ID
   gh secret set AUTOMERGE_APP_PRIVATE_KEY --app dependabot < path/to/key.pem
   ```
4. Delete the local `.pem`, or move it into your password manager. A new key can always
   be generated, so there's nothing to lose.

**Don't add the App to A9's bypass list.** Its merges must wait for the required checks
like anything else. That's the whole point of the ruleset.

### A7 — Create the CI deploy roles (after 3.1–3.2 merge)

Add this to **both** `private/instance/terraform.tfvars` and
`private/instance/dev.tfvars`:

```hcl
github_repository = "OWNER/REPO"
```

Then:

```sh
make deploy-dev && make deploy
cd terraform
terraform workspace select dev     && terraform output -raw ci_deploy_role_arn
terraform workspace select default && terraform output -raw ci_deploy_role_arn
```

This is a full apply, so read the plan output. It should add the role, its policy and
the `deployed-commit` SSM parameter. It may also show the expected Lambda drift from D3.

### A8 — Store the deploy secrets (after A7)

Per environment, use the values from A7 and your tfvars:

```sh
for env in dev prod; do
  gh secret set AWS_DEPLOY_ROLE_ARN --env $env   # paste that workspace's ci_deploy_role_arn
  gh secret set AWS_REGION          --env $env   # same region as aws_region in tfvars
  gh secret set INSTANCE_DOMAIN     --env $env   # that workspace's domain_name; used by the smoke test
done
gh variable set NAME_PREFIX --env dev  --body archivist-dev
gh variable set NAME_PREFIX --env prod --body archivist
```

Also set the two repository-level kill switches. They're what keeps a fork inert, and
they're how you pause automation without editing a workflow:

```sh
gh variable set AUTO_DEPLOY_LAMBDAS  --body true
gh variable set AUTO_RELEASE_ANDROID --body true
```

### A9 — Protect `main` and the release tags (first half now; second half after 3.3–3.4 are green, before 3.5)

**Now: require PRs and block force pushes.** First allow squash merges only. This keeps
`main` at one commit per PR, which keeps the generated release notes (3.6) readable:

```sh
gh api -X PATCH repos/{owner}/{repo} \
  -F allow_squash_merge=true -F allow_merge_commit=false -F allow_rebase_merge=false
```

Then create the branch ruleset:

```sh
gh api -X POST repos/{owner}/{repo}/rulesets --input - <<'EOF'
{
  "name": "main",
  "target": "branch",
  "enforcement": "active",
  "conditions": { "ref_name": { "include": ["~DEFAULT_BRANCH"], "exclude": [] } },
  "bypass_actors": [],
  "rules": [
    { "type": "deletion" },
    { "type": "non_fast_forward" },
    { "type": "pull_request", "parameters": {
        "required_approving_review_count": 0,
        "dismiss_stale_reviews_on_push": false,
        "require_code_owner_review": false,
        "require_last_push_approval": false,
        "required_review_thread_resolution": false } }
  ]
}
EOF
```

- **No bypass actors, the author included.** That's what "PRs are a requirement"
  means. The emergency path doesn't need one: `make deploy` from a good commit deploys
  without touching `main` (3.8).
- **Zero required approvals.** A solo author can't approve their own PR. The gate is
  the status checks (below), not review.
- From this point on, the rest of this plan lands through PRs as well.

Also create a ruleset for the release tags. Immutable releases (A1) protect a tag once
its release is published. This also covers the gap before that, and any tag a release
never got attached to:

```sh
gh api -X POST repos/{owner}/{repo}/rulesets --input - <<'EOF'
{
  "name": "android release tags",
  "target": "tag",
  "enforcement": "active",
  "conditions": { "ref_name": { "include": ["refs/tags/android-v*"], "exclude": [] } },
  "bypass_actors": [],
  "rules": [ { "type": "deletion" }, { "type": "non_fast_forward" }, { "type": "update" } ]
}
EOF
```

Creating a tag stays allowed, because both the hand-tag path and 3.6 create them.

**After 3.3–3.4 are green on `main`: require the status checks.** Settings → Rules →
`main` → add **Require status checks to pass** with `backend-ci-ok` and `android-ci-ok`.
These are the gate jobs from 3.4, and the names must match their job names exactly. Set
the source to **GitHub Actions**, so no other app can post a status with the same name.

- Don't require the individual test jobs, because a skipped job never reports.
- **Don't require "branches up to date before merging".** Dependabot would have to
  rebase every PR after each merge, and only one would merge per CI cycle. Every merge
  re-runs the full backend suite on `main` before deploying (3.4, 3.8), and the Android
  release re-runs its suite on the exact commit it ships (3.7).

The status checks are what stop auto-merge from merging a failing PR, so this half must
be done before 3.5's automerge workflow lands.

**Done when:** `git push origin main` and `git push --force origin main` are both
rejected, `git push --delete origin android-v0.3.16` is rejected, and a PR shows both
checks as required.

### A10 — Require SHA-pinned actions (right after 3.0 merges)

Turn this on **after** 3.0, never before: from that moment, any workflow that still uses
a tag reference fails to start.

```sh
gh api -X PUT repos/{owner}/{repo}/actions/permissions \
  -F enabled=true -f allowed_actions=all -F sha_pinning_required=true
gh api repos/{owner}/{repo}/actions/permissions   # confirm "sha_pinning_required": true
```

This is the enforcement half of 3.0. A PR that adds an action by tag now fails its own
checks, which are required (A9), so it can't merge. No separate lint is needed.

### A11 — Play Console check (any time before 3.7's first run)

Users and permissions → the service account → confirm it has **"Release to testing
tracks"** for this app. If you later reverse D1, it also needs "Release to production".

---

## Part B — Implementation steps

### 3.0 — Pin every action to a commit SHA

**Goal.** No workflow runs code that someone can change by moving a tag. This is a
requirement, not a nicety. The workflows hold AWS deploy rights and the Play signing
key, so a compromised or retagged action would get both.

**Files.** Every file in `.github/workflows/`. Today that's `android-ci.yml` and
`android-release.yml`, which use `actions/checkout`, `actions/setup-java`,
`gradle/actions/setup-gradle` and `actions/upload-artifact`.

**Details.**
- Replace every `uses: owner/repo@vN` with the full 40-character commit SHA of the
  latest release within that major, plus the exact version as a trailing comment:
  `uses: actions/checkout@<sha> # v4.2.2`. Dependabot recognises this form and updates
  the SHA and the comment together.
- Resolve SHAs from the action's own repository. For annotated tags, take the
  **commit** SHA, not the tag object's:
  `git ls-remote https://github.com/actions/checkout 'refs/tags/v4.2.2^{}'`. If that
  prints nothing, the tag is lightweight; drop the `^{}`.
- GitHub-owned actions too. A10's setting doesn't distinguish, and neither should we.
- Local references (`uses: ./.github/workflows/…`) stay as they are. They come from the
  same commit as the caller.
- Every action added by later steps (3.3 onwards) is pinned the same way from the start.
- Land this as its own PR, then do A10 immediately.

**Done when.** This prints nothing:
`grep -rhE '^\s*-?\s*uses:\s*[^.\s]' .github/workflows | grep -vE '@[0-9a-f]{40}(\s|$)'`.
Both existing workflows are still green with the pins, and A10 is on.

---

### 3.1 — Terraform: the CI deploy role

**Goal.** An IAM role that GitHub Actions can assume for exactly one environment of
exactly one repo, and that can do nothing except update this instance's Lambda code.

**Files.** `terraform/ci.tf` (new), `variables.tf`, `outputs.tf`, `terraform/README.md`.

**Details.**
- `variable "github_repository"`: `owner/repo`, `default = null`. If null, every
  resource in `ci.tf` has `count = 0`, so an operator who doesn't use this gets nothing.
- `data "aws_iam_openid_connect_provider"` looks up A3's provider by URL. Don't create
  it here (see A3).
- Trust policy: `aud = sts.amazonaws.com` **and**
  `sub = repo:${var.github_repository}:environment:${var.environment}`. Match the
  environment, not the branch. That ties the role to A4's environment, and the
  environment is what enforces "main only". `prod` here is the `environment` variable's
  default, so it lines up with the GitHub environment name.
- Role name `${local.name_prefix}-ci-deploy`.
- Policy, and nothing more:
  - `lambda:UpdateFunctionCode`, `lambda:GetFunction` and
    `lambda:GetFunctionConfiguration` on the three function ARNs. Reference
    `aws_lambda_function.{api,s3event,purge}.arn`; don't use a wildcard, so a fourth
    Lambda has to be granted on purpose.
  - `ssm:GetParameter`, `ssm:PutParameter` on the marker below.
- `aws_ssm_parameter` `/${local.name_prefix}/deployed-commit`, type `String`, initial
  value `unknown`, `lifecycle { ignore_changes = [value] }`. Terraform owns that it
  exists; `make deploy` and CI own what it says (3.2).
- `output "ci_deploy_role_arn"`, null when disabled.
- `max_session_duration` 3600, which is the default. Deploys take seconds.

**Done when.** `terraform plan` with `github_repository` unset shows no changes, and
with it set shows only the role, policy and parameter. After A7, an
`aws sts assume-role-with-web-identity` from a throwaway `workflow_dispatch` job in
environment `dev` succeeds. The same job without `environment: dev` is denied.

---

### 3.2 — `make deploy` records what it deployed

**Goal.** The marker always names the commit that's live, whoever deployed it. 3.8's
guard depends on this.

**Files.** `Makefile`.

**Details.**
- After a successful `terraform apply` in `deploy` / `deploy-dev`, write
  `git rev-parse HEAD` to the workspace's marker, with `-dirty` appended if
  `git status --porcelain` is non-empty. Do this with `aws ssm put-parameter --overwrite`,
  using the operator's own credentials, like the apply.
- Read the parameter name from a new `terraform output deployed_commit_parameter` rather
  than rebuilding the prefix in Make.
- Skip quietly if that output is null (CI not enabled for this instance).

**Done when.** After `make deploy-dev` from a clean tree, the parameter holds HEAD's
SHA. From a dirty tree, it holds `<sha>-dirty`.

---

### 3.3 — Backend CI

**Goal.** Nothing in `src/**` merges without typecheck, unit tests, integration tests
and a successful bundle.

**Files.** `.github/workflows/backend-ci.yml` (new).

**Details.**
- Triggers: `pull_request` and `push` to `main`. No `paths` filter at the workflow
  level; 3.4 handles skipping on PRs.
- Steps: `actions/setup-node` (version from `package.json` `engines` or `.nvmrc`; add one
  if neither exists, matching `nodejs22.x`), `npm ci`, `make test-integration` (typecheck,
  vitest, and DynamoDB Local + MinIO via `tools/local-infra.sh`; the runner has Docker),
  `npm run build`.
- Terraform job, same workflow: `terraform fmt -check -recursive`,
  `terraform init -backend=false`, `terraform validate`. It needs no AWS access.
  Dependabot's provider bumps get at least this much checking.
- Workflow-level `permissions: contents: read`. The deploy jobs that 3.8 adds to this
  same workflow raise their own permissions at job level. No other job gets secrets or
  an environment.

**Done when.** A PR shows all of it green, and a deliberately broken test on a scratch
branch turns it red.

---

### 3.4 — Gate jobs that always report

**Goal.** One required check per workflow that reports on **every** PR, whatever files
it touches.

**Files.** `.github/workflows/android-ci.yml`, `backend-ci.yml`.

**Details.**
- Drop the workflow-level `paths:` filter from `android-ci.yml`. Add an
  `on: workflow_call` trigger with a `ref` input, which 3.7 uses to run the full suite on
  the exact commit it releases.
- Add a `changes` job that computes whether `android/**` (or the workflow file)
  changed. Use `git diff --name-only` against the PR base, or `dorny/paths-filter`
  pinned by digest. Do the same in `backend-ci.yml` for `src/**`, `test/**`,
  `package*.json`, `scripts/**`, `tools/local-infra.sh`, `terraform/**`.
- **Skip only on PRs.** On `push` to `main` and on `workflow_call`, `changes` reports
  "everything changed". That matters most for the backend. Its `main` run gates the
  deploy (3.8), and the deploy ships everything between the live commit and this one,
  not just this push's changes. The backend suite only takes a few minutes, so it always
  runs on `main`. For Android, a full run on every `main` push costs more and gains
  nothing (the release re-tests anyway, see 3.7). So `android-ci` on `push` may compare
  against `github.event.before` instead. Either is acceptable; pick the simpler one when
  implementing.
- The real jobs `needs: changes` and `if:` on its output.
- Add a final gate job: `needs: [all of the above]`, `if: always()`. It fails if any
  needed job's result is `failure` or `cancelled`, and passes on `success` or `skipped`.
  Name it **`backend-ci-ok`** in `backend-ci.yml` and **`android-ci-ok`** in
  `android-ci.yml`. A required check matches on job name alone, so two jobs both called
  `ok` would be indistinguishable. A9 requires these two names.

**Done when.** A docs-only PR shows `android-ci-ok` and `backend-ci-ok` both green
within a minute. A PR touching `android/**` holds `android-ci-ok` until the instrumented
suite finishes. Every push to `main` runs the full backend suite.

---

### 3.5 — Dependabot configuration and automerge

**Goal.** Updates arrive at a pace one person can absorb, aren't the freshest possible
versions of anything, automerge only where CI really tests the change, and their merges
start workflows on `main` like any other merge.

**Files.** `.github/dependabot.yml` (new), `.github/workflows/dependabot-automerge.yml`
(new), `renovate.json` (deleted).

**Prerequisites:** A6's App exists, and A9 is complete, including its required status
checks. Without them, auto-merge won't wait for CI.

**Details.**
- Every action here is pinned by SHA (3.0). A10 would refuse to run it otherwise.
- `dependabot.yml`, `version: 2`, one `updates` entry per ecosystem:
  - `npm`, `directory: "/"`. The root `package.json` declares the workspaces.
  - `gradle`, `directory: "/android"`. It reads `gradle/libs.versions.toml`.
    **Check at implementation time whether Dependabot's Gradle support now covers the
    Gradle wrapper.** If it doesn't, wrapper bumps stay manual; note that in
    `android/AGENTS.md`.
  - `github-actions`, `directory: "/"`.
  - `terraform`, `directories: ["/terraform", "/terraform/bootstrap"]`.
- On every entry:
  - `schedule: { interval: weekly, day: saturday, time: "05:00", timezone: … }`.
  - `cooldown: { default-days: 3 }`. Malicious npm or Maven publishes are usually pulled
    within that window. This repo deploys to production unattended, so it should never
    be the first to install a new version.
  - `open-pull-requests-limit: 5`.
- **Groups**: one group per ecosystem, `patterns: ["*"]`, **`update-types: [minor,
  patch]`**. Majors stay out of the groups and arrive as individual PRs, so a group PR
  is always safe to automerge, and the automerge rule never has to reason about a
  group that mixes a major with patches.
- `@aws-sdk/*`: a bump changes nothing deployed, because `scripts/build.mjs` leaves them
  external and the runtime supplies its own. They can stay in the npm group, and 3.8
  will correctly find nothing to deploy.
- **`dependabot-automerge.yml`**:
  - `on: pull_request`, **not** `pull_request_target`. Job-level
    `if: github.event.pull_request.user.login == 'dependabot[bot]'`.
  - `permissions: { contents: read, pull-requests: read }`. `GITHUB_TOKEN` only reads;
    the App token does the writing.
  - `dependabot/fetch-metadata` (pinned) gives `update-type` and `package-ecosystem`.
  - `actions/create-github-app-token` (pinned) with `AUTOMERGE_APP_ID` and
    `AUTOMERGE_APP_PRIVATE_KEY` from the Dependabot secrets (A6), scoped to this repo.
  - Run `gh pr merge --auto --squash "$PR_URL"` with `GH_TOKEN` set to **that App
    token, not `github.token`** (D4: otherwise the merge starts nothing on `main`).
    Only do this when `update-type` isn't `version-update:semver-major` **and** the
    ecosystem isn't `terraform`. A provider bump needs a human `make deploy` anyway
    (D3), so merging it automatically would only leave it sitting unapplied.
  - `--squash`, because A9 allows no other merge method.
- Delete `renovate.json`, and remove its mention from 2.16's `STATUS.md` row.

**Done when.** The first Saturday run opens one grouped minor/patch PR per ecosystem
that has updates. Those PRs merge on their own once both required checks are green, and
**each merge
starts a `backend-ci` run on `main`**. A major or Terraform PR stays open. The repo's
Insights → Dependency graph → Dependabot tab shows every ecosystem checked with no
errors.

---

### 3.6 — Make the Android release workflow callable, and publish a GitHub Release

**Goal.** One release workflow, called both by a pushed tag and by the weekly
automation. Every version it publishes to Play also gets an immutable GitHub Release
(A1), carrying the exact bundle that was uploaded.

**Files.** `.github/workflows/android-release.yml`.

**Details.**
- Add an `on: workflow_call` with inputs `version` (e.g. `0.3.17`) and `ref`. The tag
  trigger passes `${GITHUB_REF_NAME#android-v}` and the tag itself into the same steps.
  Compute both in a first step, then use one `ARCHIVIST_VERSION_NAME` for both paths.
- **`publish` job**: `environment: play` (A2), `permissions: contents: read`. Runs
  `publishBundle` as today, then uploads
  `app/build/outputs/bundle/release/app-release.aab` as a workflow artifact. Once this
  is merged, **delete the repository-level copies** of the five secrets and confirm with
  a hand-pushed tag.
- **`github-release` job**: `needs: publish`, `permissions: contents: write`, **no
  environment**, so the Play secrets never reach the job that has write access to the
  repo.
  1. Download the artifact, rename it `archivist-<version>.aab`, and write its
     `sha256sum` alongside it.
  2. `gh release create android-v<version> --draft --target <sha> --title "Archivist
     <version>" --notes-file …` with both files. Upload the assets **while it's still a
     draft**: once published, an immutable release accepts no more.
  3. `gh release edit android-v<version> --draft=false`. Publishing makes it immutable.
     On the weekly path it also creates the tag. A tag created this way, with
     `GITHUB_TOKEN`, doesn't start workflows, so it can't publish to Play a second time.
     On the hand-tag path the tag already exists, and `--target` is ignored.
  - Notes: the PR titles merged since the previous `android-v*` tag that touched
    `android/`. One line each is enough. A9's squash-only rule makes each commit
    subject a PR title.
  - If Play publishing succeeded but this job failed, re-run just this job. Nothing
    has been published to GitHub yet, so a leftover draft can be deleted and recreated.
- `concurrency: { group: android-release, cancel-in-progress: false }` on the workflow.
  Two overlapping runs would both ask Play for the current `versionCode`
  (`resolutionStrategy AUTO`) and collide.
- Keep `publishBundle` and the internal track exactly as they are (D1).
- The bundle is signed with the upload key, not Play's app-signing key, so it isn't
  what Play delivers to devices. It's the exact artifact uploaded, which is what a
  release record should hold. It contains nothing secret: the signing key never leaves
  the runner, and the bundle is the same code the Play listing already serves.

**Done when.** A hand-pushed `android-v*` tag still publishes to Play, reading secrets
from `play`, with the repo-level copies deleted. It also produces a published GitHub
Release with the `.aab` and its checksum, and that release shows as immutable: an
attempt to delete its asset or its tag is refused.

---

### 3.7 — Weekly Android auto-release

**Goal.** If `android/**` changed since the last release, and CI passes on that exact
commit, a new patch version reaches the internal track, and gets its immutable GitHub
Release, with no human involved.

**Files.** `.github/workflows/android-auto-release.yml` (new).

**Details.**
- `on: schedule` (Sunday, after Saturday's PRs have had time to merge) plus
  `workflow_dispatch`.
- `if: vars.AUTO_RELEASE_ANDROID == 'true'` at the job level. A fork does nothing.
- **`plan` job** (`permissions: contents: read`, `fetch-depth: 0`):
  - **Find the last release** as the highest `android-v*` tag by **semver**, not
    lexically. `git tag --sort=-v:refname` puts `0.3.16` above `0.3.9`; plain `sort`
    gets this wrong.
  - Pin the commit being considered: `SHA=$(git rev-parse HEAD)`. Every later job uses
    this SHA, never a fresh `main`.
  - **Anything to ship?** `git diff --quiet <last-tag> $SHA -- android/`. No difference
    → "no Android changes", exit successfully. Who wrote the changes doesn't matter (D2).
  - Version: increment the patch of the last tag. Output `sha`, `version` and `go`.
- **`ci` job**: `uses: ./.github/workflows/android-ci.yml` with `ref: <sha>`. This is
  the full suite on the exact commit being released. It's simpler and safer than
  working out which of the week's `main` runs covered which change, and whether all of
  them passed.
- **`release` job**: `needs: [plan, ci]`, `uses: ./.github/workflows/android-release.yml`
  with the version and the SHA. That workflow publishes to Play, then creates the
  GitHub Release, and with it the tag (3.6). There's no separate tagging job.
- Play release notes are optional. Gradle Play Publisher reads
  `android/app/src/main/play/release-notes/en-US/internal.txt`. Reusing the GitHub
  Release notes from 3.6 is a nice touch, but the internal track doesn't require notes.

**Done when.** A `workflow_dispatch` run with Android changes since the last tag (human,
Dependabot, or both) runs the full Android suite, produces a new internal-track release,
and creates the matching immutable GitHub Release and tag. A run with no Android changes
exits as a no-op.

---

### 3.8 — Lambda deploy on every merge to `main`

**Goal.** Every merge to `main` that changes a Lambda bundle goes live on `dev`,
gets smoke tested, then goes live on `prod`, with no human involved.

**Files.** `.github/workflows/backend-ci.yml`. The deploy is two more jobs in the same
workflow, not a separate one: the commit that just passed CI is the one that deploys,
with no `workflow_run` or cross-workflow SHA passing.

**Details.**
- Two jobs, `deploy-dev` then `deploy-prod` (`needs: deploy-dev`). `deploy-dev` needs
  the test and build jobs. Both have
  `if: github.event_name == 'push' && github.ref == 'refs/heads/main' &&
  vars.AUTO_DEPLOY_LAMBDAS == 'true'`.
- Each sets `environment:`, `permissions: { id-token: write, contents: read }`,
  `fetch-depth: 0`, and `concurrency: { group: deploy-<env>, cancel-in-progress: false }`.
  With that setting, a running deploy finishes, and of the merges queued behind it only
  the newest runs. That's correct, because each run deploys everything between the live
  commit and its own.
- In each deploy job, with `SHA` = `github.sha`:
  1. `aws-actions/configure-aws-credentials` (pinned) with `role-to-assume` and
     `aws-region` from the environment secrets.
  2. Read the marker. Missing, `unknown`, or `*-dirty` → **fail**, with "deploy once with
     `make deploy[-dev]` from a clean tree". CI won't guess what's live. If the marker
     equals `SHA`, finish: nothing to do.
  3. **Infrastructure guard (D2):** if `git diff --name-only <marker> <SHA> --
     terraform/` is non-empty → **skip** with a `::warning::` annotation, "infrastructure
     changes pending; run `make deploy`". Use `git diff`, not `git log`, so the check
     also works when `make deploy` from an older checkout left the marker ahead of or
     beside `main`.
  4. Build at `<marker>` and at `<SHA>` (two `git worktree`s, `npm ci && npm run build`
     in each). Diff `dist/<name>/` per function. Only changed functions deploy. If
     nothing changed, write the new marker and finish. That's the normal outcome for
     devDependency or `@aws-sdk/*` bumps, and for tests-only or docs-only commits.
  5. For each changed function: zip `dist/<name>/` the way `data.archive_file` does
     (`index.mjs` plus its sourcemap at the zip root), run
     `aws lambda update-function-code --function-name ${NAME_PREFIX}-<name> --zip-file
     fileb://…`, then `aws lambda wait function-updated-v2`.
  6. Smoke test: `curl -fsS https://$INSTANCE_DOMAIN/api/health`, retried for about 30s.
     Then confirm `LastUpdateStatus == Successful` for every updated function. A module
     that fails to load (the likely failure for a dependency bump) shows up in the API
     smoke test. It doesn't show up for `s3event`/`purge`, which only run on events.
     Treat that as a known gap rather than inventing a test invocation (see open
     questions).
  7. Only after the smoke test passes, write `<SHA>` to the marker. A failed run leaves
     the marker where it was, so the next merge (or a re-run) redeploys the same range.
- **Rollback is a revert PR.** That's the same path as any other change, and CI tests
  the revert. For an emergency where waiting for CI is too slow, run
  `make deploy` from the last good commit. It moves the marker (3.2), and the next merge
  to `main` deploys forward from there. Document both in the runbook (3.10).

**Done when.**
- An automerged Dependabot PR that changes a bundle, and separately a PR the author
  merges by hand, each leave both environments running the new code, with their markers naming
  that commit and `/api/health` returning 200 on both.
- A merge that changes no bundle updates only the markers.
- A merge touching `terraform/**` skips the deploy with the warning, and the next merge
  after `make deploy` deploys normally.
- A failing `dev` smoke test stops the run before `prod`. To check this, point `dev`'s
  `INSTANCE_DOMAIN` secret at a host that doesn't resolve and merge a bundle-changing
  PR. `prod` must be untouched, and `dev`'s marker must not move. Restore the secret
  and re-run the failed jobs.

---

### 3.9 — Failures go somewhere a human will see

**Goal.** A broken unattended run isn't silent. The triggering actor is Dependabot's
merge (the App) or the scheduler, so GitHub's default failure email may reach no one
useful.

**Files.** `android-auto-release.yml`, `backend-ci.yml` (only for `push` runs on `main`;
a red PR is already visible on the PR).

**Details.** Add a final `if: failure()` job that opens an issue labelled
`automation-failure`, or comments on the existing open one, with the run URL. It needs
`permissions: issues: write`, and `GITHUB_TOKEN` is enough. Skips (D2 guards) aren't
failures and must not open issues. That includes the infrastructure guard: it's the
expected result of every Terraform commit, and its warning annotation is enough. A
Dependabot PR whose CI fails just stays open: it shows on the PR list and needs no
issue.

**Done when.** A forced failure opens one issue, and a second forced failure comments on
it rather than opening another.

---

### 3.10 — Documentation

**Files.**
- `docs/ops/automated-updates.md` (new): Part A rewritten as a verified runbook once
  every command has actually been run, plus pausing (the two variables), rollback (3.8),
  "the deploy skipped on pending infrastructure, now what", the "`main` is always
  releasable" rule (D2), and "a Dependabot PR is stuck": a major, a Terraform bump, or
  red CI.
- `docs/design/android.md` "CI and release, as built": the `play` environment, the
  weekly release, and the immutable GitHub Release per version.
- `docs/ops/android-release.md`: a hand-pushed tag now also creates a GitHub Release,
  and release tags can't be moved or deleted (A1, A9). A mistaken tag is fixed by
  releasing the next patch version, never by retagging.
- `docs/ops/android-release-secrets.md`: secrets now live in the `play` environment.
- `docs/design/deployment.md`: one paragraph saying CI deployment is an opt-in operator
  convenience, nothing in it is required to self-host, and a fork is inert by default.
- `terraform/README.md`: `ci.tf`, `github_repository`, and D3's expected drift.
- `CLAUDE.md`: the new runbook in the file list, plus a short rule for agents: `main`
  only changes through PRs (A9), every action is pinned by SHA (3.0, A10), and a new
  workflow step that uses an action pins it the same way.
- `STATUS.md`: the Plan 03 rows.

**Done when.** Someone who has never seen this repo could follow
`automated-updates.md` to enable it on their own fork.

---

### 3.11 — End-to-end verification

**Goal.** Prove the whole path once, with real versions.

**Details.** Wait for a Saturday run, or trigger one from the Dependabot tab ("Check for
updates"), and get one backend and one Android minor/patch update. Watch each one. For
the backend: PR → checks → automerge (by the App) → `backend-ci` on `main` → deploy to
`dev` → deploy to `prod`. For Android: Sunday's (or a dispatched) `android-auto-release`
→ internal track → immutable GitHub Release → installed on a test device.
Record run IDs in `STATUS.md`. Don't record domains or account IDs.

**Done when.** That has happened once, end to end, with no hand intervention.

---

## Open questions

- **Smoke-testing the event-driven Lambdas.** A dependency that breaks module load in
  `s3event` or `purge` goes unnoticed until the next upload or the daily sweep. Options:
  a `{ "archivistPing": true }` short-circuit in each handler, invoked after deploy
  (this needs `lambda:InvokeFunction` on the role); or Lambda aliases with an automatic
  rollback alarm. Both cost more than this plan does. Revisit if it ever bites.
