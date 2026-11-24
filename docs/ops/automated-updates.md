# Automated updates and delivery

How to turn on, and then run, the automation from
[plan 03](../plans/03-automated-updates.md): Dependabot opens dependency PRs, CI gates
them, minor and patch updates merge on their own, merged Lambda code deploys to `dev` and
then `prod`, and the app reaches the Play internal track weekly.

All of it is optional. An instance runs fine with `make deploy` from a laptop and none
of this (see `docs/design/deployment.md`). A fork does nothing until its operator goes
through the setup below.

> **Status.** The commands below come from the plan and have **not all been run
> against a live repository yet**. Plan step 3.23 asks for this to become a verified
> runbook once they have; `docs/plans/STATUS.md` (Plan 03) records which steps have
> actually been done. When you run a step, fix anything here that turned out wrong.

Every `gh api` command below uses `{owner}/{repo}`, which `gh` fills in from the
current checkout. Nothing here, or in any committed file, should name your domain,
AWS account or role ARN: those values go into GitHub secrets.

## The rules this sets up

- **`main` is always releasable (D2).** Every merge to `main` that changes a Lambda
  bundle deploys to production minutes later, and the weekly run ships whatever is under
  `android/`. Whoever wrote the commit doesn't matter. So `main` only changes through a
  PR whose checks passed. Nobody can push or force-push to it directly, not even the
  author.
- **CI deploys code, never infrastructure (D3).** The CI role can update the three
  Lambdas' code and one SSM parameter, nothing else. Terraform changes, Dependabot's
  provider bumps included, ship through `make deploy`.
- **Every action is pinned to a commit SHA**, with the version as a comment
  (`uses: actions/checkout@<40-hex sha> # v4.4.0`). The repository refuses to run
  anything else. Dependabot keeps the pins current.

## One-time setup

Order matters where noted. Some steps must wait for a specific PR to merge.

### 1. Lock down Actions defaults; enable release immutability

Settings → Actions → General:

- **Workflow permissions → "Read repository contents and packages permissions".**
- **"Allow GitHub Actions to create and approve pull requests" → off.**
- **Fork pull request workflows → "Require approval for all outside collaborators".**

Settings → General → Releases: **Enable release immutability.**

### 2. Move the Play secrets into an environment

```sh
gh api -X PUT repos/{owner}/{repo}/environments/play \
  -f 'deployment_branch_policy[protected_branches]=false' \
  -F 'deployment_branch_policy[custom_branch_policies]=true'
gh api -X POST repos/{owner}/{repo}/environments/play/deployment-branch-policies -f name=main -f type=branch
gh api -X POST repos/{owner}/{repo}/environments/play/deployment-branch-policies -f name='android-v*' -f type=tag
```

Then set the five secrets with `--env play`, as in `docs/ops/android-release-secrets.md`.
Leave the repository-level copies in place until step 13.

### 3. Create the GitHub OIDC provider in AWS

There's one per AWS account, shared by every workspace, so it isn't in Terraform.

```sh
aws iam list-open-id-connect-providers   # look for token.actions.githubusercontent.com
aws iam create-open-id-connect-provider \
  --url https://token.actions.githubusercontent.com \
  --client-id-list sts.amazonaws.com
```

### 4. Create the `dev` and `prod` environments

```sh
for env in dev prod; do
  gh api -X PUT repos/{owner}/{repo}/environments/$env \
    -f 'deployment_branch_policy[protected_branches]=false' \
    -F 'deployment_branch_policy[custom_branch_policies]=true'
  gh api -X POST repos/{owner}/{repo}/environments/$env/deployment-branch-policies -f name=main -f type=branch
done
```

Don't add required reviewers to `prod`: the protection is `dev` deploying first and
passing a smoke test. A wait timer of a few minutes on `prod` is fine if you want a
window to cancel.

### 5. Turn on Dependabot alerts and security updates

Settings → Code security: **Dependabot alerts**, **Dependabot security updates** and
**Grouped security updates** → Enable.

### 6. Create the automerge GitHub App

An automerged PR has to start workflows on `main`, and merges made with `GITHUB_TOKEN`
don't. So the automerge workflow uses a token from a small App you own.

Account Settings → Developer settings → GitHub Apps → New GitHub App:

- Any unique name; homepage URL: the repository.
- **Webhook: uncheck "Active".**
- Repository permissions, and nothing else: **Contents**, **Pull requests** and
  **Workflows**, all *Read and write*. Metadata is read-only, and mandatory.
- **Only on this account.**

Then generate a private key, note the **App ID**, install the App on **only this
repository**, and store both as **Dependabot** secrets:

```sh
gh secret set AUTOMERGE_APP_ID --app dependabot          # paste the App ID
gh secret set AUTOMERGE_APP_PRIVATE_KEY --app dependabot < path/to/key.pem
```

Delete the local `.pem`. **Never add the App to a ruleset's bypass list.**

### 7. Protect `main` and the release tags

```sh
gh api -X PATCH repos/{owner}/{repo} \
  -F allow_squash_merge=true -F allow_merge_commit=false -F allow_rebase_merge=false

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

Check: `git push origin main`, `git push --force origin main` and
`git push --delete origin android-v<some existing version>` are all rejected. From now
on, everything lands through a PR.

### 8. Play Console permission check

Users and permissions → the service account → it needs **"Release to testing tracks"**
for this app.

### 9. Require SHA-pinned actions (right after the pinning PR merges)

Not before: from this moment, any workflow that uses an action by tag fails to start.

```sh
gh api -X PUT repos/{owner}/{repo}/actions/permissions \
  -F enabled=true -f allowed_actions=all -F sha_pinning_required=true
gh api repos/{owner}/{repo}/actions/permissions   # "sha_pinning_required": true
```

### 10. Apply the CI deploy role (after the Terraform PR merges)

Add the repository to **both** tfvars files (`private/instance/terraform.tfvars` and
`private/instance/dev.tfvars`), then deploy both from a clean, up-to-date `main`:

```hcl
github_repository = "OWNER/REPO"
```

```sh
make deploy-dev && make deploy
cd terraform
terraform workspace select dev     && terraform output -raw ci_deploy_role_arn
terraform workspace select default && terraform output -raw ci_deploy_role_arn
```

The apply adds the role, its policy and the `/<prefix>/deployed-commit` SSM parameter,
and `make deploy` writes the deployed commit into it.

### 11. Store the deploy secrets and the kill switches

```sh
for env in dev prod; do
  gh secret set AWS_DEPLOY_ROLE_ARN --env $env   # that workspace's ci_deploy_role_arn
  gh secret set AWS_REGION          --env $env   # the aws_region from your tfvars
  gh secret set INSTANCE_DOMAIN     --env $env   # that workspace's domain_name
done
gh variable set NAME_PREFIX --env dev  --body archivist-dev
gh variable set NAME_PREFIX --env prod --body archivist

gh variable set AUTO_DEPLOY_LAMBDAS  --body false
gh variable set AUTO_RELEASE_ANDROID --body false
```

### 12. Require the status checks (once both CI workflows are green on `main`)

Settings → Rules → the `main` ruleset → **Require status checks to pass**:
`backend-ci-ok` and `android-ci-ok`, source **GitHub Actions**. Don't require the
individual test jobs (a skipped job never reports), and don't require branches to be up
to date. This must happen before the automerge workflow merges.

### 13. Delete the repository-level Play secrets (after the callable release merges)

First push a test release tag and confirm it publishes, reading the `play` environment's
secrets. Then:

```sh
for s in ANDROID_KEYSTORE_BASE64 ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_ALIAS \
         ANDROID_KEY_PASSWORD PLAY_SERVICE_ACCOUNT_JSON; do
  gh secret delete "$s"
done
gh secret list   # none of them
```

### 14. Switch it on

- Once the weekly release workflow has merged: `gh variable set AUTO_RELEASE_ANDROID
  --body true`, then dispatch it once (`gh workflow run android-auto-release.yml`).
- Once the Lambda deploy has merged: `gh variable set AUTO_DEPLOY_LAMBDAS --body true`.
  The next merge to `main` is the first CI deploy.

## Running it

### Pausing

Set either variable to anything but `true`. Nothing else needs to change.

```sh
gh variable set AUTO_DEPLOY_LAMBDAS  --body false   # merges stop deploying
gh variable set AUTO_RELEASE_ANDROID --body false   # Sundays stop releasing
```

Dependabot keeps opening PRs, and they keep automerging into `main`. To stop that too,
disable the `Dependabot automerge` workflow (`gh workflow disable dependabot-automerge.yml`).

### Rollback

**Normally: a revert PR.** It goes through CI like anything else, and its merge deploys.

**In an emergency**, when waiting for CI is too slow: check out the last good commit and
run `make deploy` (or `make deploy-dev`) from a clean tree. That moves the
deployed-commit marker to that commit, and the next merge to `main` deploys forward from
there. Then land the fix or revert on `main` through a PR as usual.

### "Infrastructure changes pending; run `make deploy`"

The deploy job found a difference under `terraform/` between the commit that's live and
the one it was about to ship, so it skipped rather than ship code ahead of
infrastructure it may need. This is the expected result of every Terraform commit, not a
failure. When `dev` skips this way, `prod` waits too.

Run `make deploy-dev` and `make deploy` from a clean, up-to-date `main`. They apply the
Terraform, deploy the code and move the markers, so there's nothing left for that run
to do. The next merge deploys normally.

### The deploy failed: "the deployed-commit marker is …"

CI won't guess what's live. `unknown` (never deployed since the parameter was created),
a `-dirty` suffix (the last `make deploy` was from a modified tree) or a missing
parameter all mean the same thing: `make deploy[-dev]` once from a clean tree.

### A failure issue appeared

Failed runs on `main` and failed weekly releases open an issue labelled
`automation-failure`, or comment on the open one, with the run URL. Fix the cause, then
close the issue. A failed deploy leaves the marker where it was, so re-running the
failed jobs, or the next merge, redeploys the same range. If Play publishing succeeded
but the GitHub Release job failed, re-run just that job.

### A Dependabot PR is stuck

- **A major update.** These never automerge. Read the changelog, fix what breaks on the
  PR's branch if needed, and merge it yourself.
- **A Terraform update**, of any size. A provider bump only takes effect through
  `make deploy`, so these never automerge. Merge it, then run `make deploy-dev` and
  `make deploy`. Until you do, every Lambda deploy skips with the warning above.
- **Red CI.** It stays open and needs a human; no issue is opened for it. Push a fix
  onto the branch, or close it and let next Saturday's run try again. Commits a human
  pushes onto a Dependabot branch are never automerged; merge it yourself once it's
  green.
