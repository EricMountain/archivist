# Automated updates and delivery

How to turn on, and then run, the automation from
[plan 03](../plans/03-automated-updates.md): Dependabot opens dependency PRs, CI gates
them, minor and patch updates merge on their own, merged Lambda code deploys to `dev` and
then `prod`, and the app reaches the Play internal track weekly.

All of it is optional. An instance runs fine with `make deploy` from a laptop and none
of this (see `docs/design/deployment.md`). A fork does nothing until its operator goes
through the setup below.

> **Status.** Setup steps 1–14 were run against the author's instance in November
> 2026 and corrected where they turned out wrong; `docs/plans/STATUS.md` (Plan 03)
> records what each one showed. Under "Running it", the rollback and
> infrastructure-pending paths haven't been needed yet. When something here turns out
> wrong, fix it.

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
  -F 'deployment_branch_policy[protected_branches]=false' \
  -F 'deployment_branch_policy[custom_branch_policies]=true'
gh api -X POST repos/{owner}/{repo}/environments/play/deployment-branch-policies -f name=main -f type=branch
gh api -X POST repos/{owner}/{repo}/environments/play/deployment-branch-policies -f name='android-v*' -f type=tag
```

Use `-F` (typed), not `-f`, for both booleans: `-f` sends the string `"false"`.

Then set the five secrets with `--env play`, as in `docs/ops/android-release-secrets.md`.
GitHub never shows a secret's value again, so you need the originals (keystore file,
passwords, alias, service-account JSON). Leave the repository-level copies in place until
step 13: the tag-triggered release reads them until the callable release merges.

### 3. Create the GitHub OIDC provider in AWS

There's one per AWS account, shared by every workspace, so it isn't in Terraform. Use
the same credentials as `make deploy` (add `--profile …` if that's how you select them).

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
    -F 'deployment_branch_policy[protected_branches]=false' \
    -F 'deployment_branch_policy[custom_branch_policies]=true'
  gh api -X POST repos/{owner}/{repo}/environments/$env/deployment-branch-policies -f name=main -f type=branch
done
```

Don't add required reviewers to `prod`: the protection is `dev` deploying first and
passing a smoke test. A wait timer of a few minutes on `prod` is fine if you want a
window to cancel.

### 5. Turn on Dependabot alerts and security updates

```sh
gh api -X PUT repos/{owner}/{repo}/vulnerability-alerts
gh api -X PUT repos/{owner}/{repo}/automated-security-fixes
```

**Grouped security updates** has no API: Settings → Advanced Security (older UI: Code
security) → Dependabot → enable it.

Security-update PRs opened *before* `.github/dependabot.yml` exists come from GitHub's
default config, and once the config lands Dependabot refuses to rebase them ("The
dependabot.yml entry that created this PR has been deleted"). Close them; it recreates
any still needed under the new config.

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

The `main` ruleset comes back with GitHub's defaults
`require_extra_approval_for_unattributed_changes: true` and all three
`allowed_merge_methods`. Neither gets in the way: self-authored and Dependabot PRs merge
without an approval, and the repository setting above already limits merges to squash.

Check that each of these is rejected with `GH013`. None of them changes anything if it
is (the commits are throwaway objects with `main`'s own tree):

```sh
c=$(git commit-tree -p origin/main -m test origin/main^{tree});   git push origin "${c}:refs/heads/main"
s=$(git commit-tree -p origin/main~1 -m test origin/main^{tree}); git push --force origin "${s}:refs/heads/main"
git push --delete origin android-v<some existing version>
```

Quote `"${c}:…"` in zsh: unquoted, `$c:r` is parsed as a history modifier. From now on,
everything lands through a PR.

### 8. Play Console permission check

Users and permissions → the service account → it needs **"Release to testing tracks"**
for this app, and nothing more: automation stops at the internal track, so leave
"Release to production" off.

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
# If `gh api repos/{owner}/{repo}/actions/oidc/customization/sub` reports
# "use_immutable_subject": true, also copy its sub_claim_prefix:
github_oidc_subject_prefix = "repo:OWNER@<owner id>/REPO@<repo id>"
```

```sh
make deploy-dev && make deploy
cd terraform
terraform workspace select dev     && terraform output -raw ci_deploy_role_arn
terraform workspace select default && terraform output -raw ci_deploy_role_arn
```

The apply adds the role, its policy and the `/<prefix>/deployed-commit` SSM parameter,
and `make deploy` writes the deployed commit into it. Check that both markers hold a clean
SHA (pass `--region` if your shell has no default region):

```sh
terraform output -raw deployed_commit_parameter \
  | xargs -I{} aws ssm get-parameter --region <region> --name {} --query Parameter.Value --output text
```

The OIDC provider (step 3) must exist first, or the apply fails. The role trusts
exactly `<prefix>:environment:ENV`, where the prefix is `github_oidc_subject_prefix` if
set, else `repo:OWNER/REPO`. If the first CI deploy fails with "Not authorized to perform
sts:AssumeRoleWithWebIdentity", the prefix doesn't match what GitHub sends: re-check the
`customization/sub` output above. Don't otherwise customise the subject claim.

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

From the CLI, `PUT` the whole ruleset: an update replaces its rule list, so repeat the
rules from step 7 and add this one (15368 is the GitHub Actions app, so no other app can
post a passing check under the same name; confirm it from a check run's `app.id`):

```json
{ "type": "required_status_checks", "parameters": {
    "strict_required_status_checks_policy": false,
    "do_not_enforce_on_create": false,
    "required_status_checks": [
      { "context": "backend-ci-ok", "integration_id": 15368 },
      { "context": "android-ci-ok", "integration_id": 15368 } ] } }
```

```sh
gh api repos/{owner}/{repo}/rulesets   # find the main ruleset's id
gh api -X PUT repos/{owner}/{repo}/rulesets/<id> --input ruleset.json
```

To prove it, open a throwaway PR with a deliberately failing test, mark it ready for
review (a draft can't merge anyway, which hides the result), and check that
`gh pr view <n> --json mergeStateStatus` says `BLOCKED`. Then close it unmerged.

### 13. Delete the repository-level Play secrets (after the callable release merges)

First push a test release tag and confirm it publishes, reading the `play` environment's
secrets. A job that declares `environment: play` gets the environment's copy of a secret
even while a repository-level one with the same name exists, so the repository copies are
unused leftovers from here on; deleting them means nothing running on another branch can
read the signing key. Then:

```sh
for s in ANDROID_KEYSTORE_BASE64 ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_ALIAS \
         ANDROID_KEY_PASSWORD PLAY_SERVICE_ACCOUNT_JSON; do
  gh secret delete "$s"
done
gh secret list   # none of them
```

The release created for that tag should be immutable: `gh api
repos/{owner}/{repo}/releases/tags/<tag> --jq .immutable` is `true`, and deleting its
`.aab` (`gh api -X DELETE repos/{owner}/{repo}/releases/assets/<asset id>`) is refused
with 422.

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

### "Already runs …, which includes …"; or the live commit is behind `main`

Several merges at once (a Saturday Dependabot batch) queue several deploys. Each
environment runs one at a time, and of those waiting only the one queued last survives;
the rest show as cancelled. That's the run whose tests finished last, which isn't always
the newest commit. So:

* A run whose commit is older than the live one skips with "already runs …, which
  includes …". Nothing rolls back.
* The live commit can stop one or two merges short of `main`. The next merge catches up,
  since each deploy ships everything between the live commit and its own. To catch up
  now, re-run the newest cancelled run's failed jobs:
  `gh run rerun <run-id> --failed`.

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
- **Red CI.** It stays open and needs a human; no issue is opened for it. Usually the
  cleanest fix is a separate PR to `main` that makes the update possible (for example a
  `compileSdk` bump a new AndroidX library requires, or taking one awkward library out of
  the group by bumping it yourself together with the code it needs). Dependabot then
  rebases the PR, or closes a group PR as "updatable in another way" and opens a fresh
  one on its next run. Insights → Dependency graph → Dependabot → *Check for updates*
  runs it now. Alternatively push a fix onto the branch; commits a human pushes onto a
  Dependabot branch are never automerged, so merge it yourself once it's green.
- **"Dependabot cannot open any more pull requests"** in the dependency graph. That
  ecosystem has reached `open-pull-requests-limit` (5), usually with majors waiting for
  you. Merge, close, or `@dependabot ignore this major version` some of them; until then
  no new PR for that ecosystem opens, security updates included.
