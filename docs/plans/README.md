# Development plans

Ordered, implementable plans. Work through them in sequence.

| Plan | Scope |
| --- | --- |
| [01-aws-backend.md](01-aws-backend.md) | Every AWS resource: Cognito, API Gateway, Lambdas, CloudFront, plus the shared key-building library and the crypto format spec |
| [02-android-mvp.md](02-android-mvp.md) | Archivist MVP: connect, sign in, back up, browse |

**[STATUS.md](STATUS.md) tracks progress against both, step by step.** Read it before
starting work here; update it in the same change when you finish. It is the source of
truth for "is X done" — not this README, not a previous session's summary, and not a
step's files simply existing.

## How to use these

Each step has a **Goal**, the **Files** it touches, **Details** that constrain the
implementation, and **Done when** — an observable condition, not a feeling. Work one
step at a time and finish it before starting the next.

Steps within a phase are ordered by dependency. Where two are genuinely independent it
says so.

## Ground rules for whoever implements this

**The design documents are authoritative.** `docs/design/design.md` defines the data
model; `docs/design/sample-data.md` shows it as real rows and real queries. Do not
invent key structures, attribute names or item types — look them up. If a plan step and
the design disagree, the design wins and the plan has a bug worth reporting.

**If you change the data model, update `sample-data.md` in the same change.** That rule
is in `CLAUDE.md` and it exists because a stale sample contradicts the design silently.

**Read `STATUS.md` before starting a step, update it after.** Same reasoning as the
sample-data rule, same place it's defined: `CLAUDE.md`'s "Keep the status file in sync".
A step marked "done" there might not be — verify before trusting it, especially before
building a later step on top of it. When you finish, mark exactly what you verified
(a test run, a curl, a real deployment) versus what you didn't; don't upgrade a step to
"done" on the strength of code existing alone.

**Decisions in the design are settled.** Encryption is client-side, timestamps are UTC,
identity is a ULID, deletion is soft, the app is self-hosted. These were argued through
and the reasoning is recorded. Don't reopen them mid-implementation; if something is
genuinely unworkable, stop and say so rather than quietly substituting an alternative.

**Nothing personal in committed files.** No real names, no real domains, no account IDs,
no hostnames of anyone's machines. Use `photos.example.com` and generic phrasing like
"the author" or "a home server". Real config lives in `private/` behind symlinks — see
`private/README.md`.

Before committing, grep the tree for the maintainer's own name, domain and machine
names. Don't write those strings into a committed file *including as part of the check
itself* — keep the search terms in `private/`, or type them ad hoc.

`fr.enry.archivist` is the one allowed exception: it's the published Android application
ID, permanent and already public.

**Do not add analytics, crash reporting or any third-party SDK that transmits data.**
The privacy policy's central claim is that the app sends nothing to the author. A single
casually-added dependency invalidates a published legal document. If crash reporting
becomes necessary, it's a self-hosted endpoint and a documentation change first.

**Ask before inventing an API shape.** Where a plan step names endpoints, use those. The
Android app and any future web client both depend on them.

## What a human has to do first

These can't be automated and block specific steps:

| Prerequisite | Blocks |
| --- | --- |
| AWS credentials in the environment (`AWS_PROFILE`) | everything in plan 01 |
| A domain in Route 53 | step 1.14 |
| Google Play declaration for broad media permissions | shipping, not building |
| Play upload keystore + service account | step 2.16 |
