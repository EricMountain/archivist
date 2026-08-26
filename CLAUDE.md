# archivist

A "serverless" photo library on S3 + DynamoDB + Lambda + CloudFront. Design stage —
no implementation yet.

**Self-hosted.** Every user deploys the whole stack into their own AWS account under
their own domain. There is no shared service and no developer-operated backend;
`photos.example.com` is the author's own invite-only instance, not "the service". Nothing
in the Terraform or the app may hardcode a domain, account or region. See
`docs/design/deployment.md`.

## Files

* `docs/plans/` — ordered, implementable build plans. Start at `docs/plans/README.md`.
* `docs/plans/STATUS.md` — step-by-step implementation status for both plans. **Read
  this before starting any plan-related work, and update it in the same change when you
  finish (or partially finish, or find a step already broken).** See "Keep the status
  file in sync" below.
* `docs/design/design.md` — the design plan. The authoritative document.
* `docs/design/deployment.md` — the self-hosting model, and the author/operator/user
  split that determines who is responsible for what.
* `docs/design/crypto-format.md` — the wire format spec: object encryption, key
  wrapping, byte-range arithmetic, and the conformance vectors every client must pass.
  Authoritative over prose in `design.md` wherever the two describe bytes.
* `docs/design/sample-data.md` — worked example of ~8 assets as actual DynamoDB rows,
  plus every access pattern as a real query against those rows.
* `docs/design/android.md` — design and tech stack for **Archivist**, the Android
  client (`fr.enry.archivist`).
* `docs/ops/` — operator runbooks: step-by-step, command-verified how-tos for running a
  deployed instance (e.g. `create-user.md`). Different job from `deployment.md`, which
  explains the model; these explain the exact commands.
* `docs/play/data-safety.md` — answers for the Play Console Data Safety form.
* `docs/play/privacy-policy.md` — the app's published privacy policy. Covers software
  that receives nothing; its central claim is "the app sends nothing to the author",
  which a single analytics SDK would invalidate.
* `docs/play/instance-privacy-policy.md` — template for an operator running an instance
  that other people use. Separate document, separate controller. Don't merge the two.

Both must be re-checked whenever the app gains analytics or crash reporting, changes
what metadata leaves the device, or changes what the server can read. They answer the
same questions in different formats and must not drift apart.
* `terraform/` — infrastructure. Flat root module + `bootstrap/` for the state bucket.
* `docs/design/thoughts.md` — symlink into `private/`. The user's running brainstorm:
  context, not decisions. Don't edit it, and don't quote it in committed files.

## Nothing personal in the committed tree

This repository is meant to be publishable. **No committed file may name a person, a
domain, an AWS account, or anything else specific to one deployment.** Use
`photos.example.com`, "the author", "a home server". If a doc needs to reference real
config, the real config goes in `private/` with a symlink — see `private/README.md`.

The single exception is `fr.enry.archivist`, the Android application ID: permanent once
published, already public on the Play Store, and documentation would be wrong without
it.

Before suggesting a commit, grep the tree for the maintainer's name, domain and machine
hostnames. Don't hardcode those strings anywhere committed — not even inside the check
command, which would defeat the purpose.

## Naming

Every AWS resource is prefixed `archivist`. `local.name_prefix` in
`terraform/locals.tf` is the single place that's decided; non-prod environments insert
the environment name (`archivist-dev-media`). Buckets append the account ID.

## Keep the sample data in sync

**`docs/design/sample-data.md` must be updated whenever the data model in `design.md`
changes.**
It is not a one-off illustration; it's how the schema gets sanity-checked, and a stale
version is worse than none because it silently contradicts the design.

This means any change to key structure, item types, attribute names, GSI keys or
projections. Update it in the same turn as the design change, not later. If a change
makes an existing sample row impossible, that's a signal worth raising rather than
quietly rewriting the row.

The same applies to its Queries section: a new access pattern needs a worked query, and
a changed key structure invalidates the existing ones.

The sample deliberately covers the awkward cases — multi-rendition assets, missing
EXIF, timestamp ties, chunked encryption. Keep that property: when the design grows a
new edge case, the sample should grow an asset that exercises it.

## Keep the status file in sync

**`docs/plans/STATUS.md` is the source of truth for what's implemented.** Not memory of
a past session, not a file's mere existence — a file can exist and still not meet its
plan step's "Done when". This applies to every agent working from the plans, not just
whichever one wrote a given step: a fresh session has no memory of what a previous one
(human or agent) did, and `git log` alone doesn't say whether a step's "Done when" was
actually met.

**Before starting any plan step:** read `STATUS.md` for that step's current status
rather than assuming it's untouched, and rather than assuming a prior "done" is still
accurate — re-verify anything you're about to build on top of.

**After finishing a change that touches a plan step** — whether you completed it, got
partway, or just discovered it's broken or already done — update `STATUS.md` in the same
change. Say what you verified (a test run, a curl, a deployment) and what you didn't;
"partial" with a precise gap is more useful than an optimistic "done".

This mirrors the sample-data rule above: a stale status is worse than none, because it
silently contradicts the repo instead of admitting it doesn't know.

## Design conventions

* Timestamps stored UTC, ISO-8601, fixed width. Offsets kept separately.
* Composite keys use `#`, with the variable-length user-controlled field last.
* IDs are ULIDs.
* Decisions and their rationale go in `design.md`; unresolved ones go in its
  "Open questions" section rather than being silently defaulted.
